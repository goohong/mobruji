package com.mobruji.song.application.catalogimport;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

/**
 * MusicBrainz browse 기반 곡 카탈로그 대량 임포트 — spec {@code song-catalog-expansion.md} §5-3/§5-4 (#1705).
 *
 * <p>{@link MetadataOnlyImportCommand}(수기 (title, artist) 후보 1건씩 검색) 와 달리, 수기 큐레이션 한국
 * 아티스트 시드({@code classpath:/korean-artists-seed.json}) 를 입력으로 아티스트별 recording 을 페이징 browse 해
 * **곡 자체를 발견**(discover)하며 후보 풀을 대량으로 확장한다. 차트 사이트 자동 크롤은 하지 않는다 — 아티스트
 * 시드는 운영자가 차트를 수기 참고해 정리한 합법 경로다 (spec §2-1, #1640).
 *
 * <p>각 recording 은 CC0 메타(제목/아티스트/MBID/ISRC/연도/장르)만 {@link Song} 으로 upsert 한다 —
 * {@link MetadataSource#EXTERNAL_API}, key {@link MusicalKey#UNKNOWN}, 음역대/bpm 미설정. 음역대는 외부가
 * 제공하지 않으며 자체 분석({@code song-self-analysis-pipeline.md}) 큐가 후속한다 (spec §5-0). 추천 점수 산식
 * 입력(음역대)이 채워지지 않아 결정성 회귀가 없다 (spec §6 보호 영역 사유).
 *
 * <p>멱등성 (spec §3) — MBID / ISRC / (title, artist) 중 하나라도 이미 존재하면 skip 한다. DB 조회에 더해 같은
 * 실행 내 중복(여러 페이지·여러 아티스트가 공유하는 recording)도 in-memory 집합으로 한 번 더 막는다.
 *
 * <p>rate limit (spec §5-3) — 호출 throttle/backoff 는 {@link MusicBrainzBrowseClient} 가 담당하고, 503 backoff
 * 소진({@link CatalogBrowseRateLimitException}) 시 batch 전체를 즉시 중단한다. 그 외 페이지/곡 단위 실패는
 * 통계만 누적하고 다음으로 진행한다 (단위 격리).
 *
 * <p>규모 상한 — 아티스트 수({@code maxArtists}) × 아티스트당 recording 수({@code maxRecordingsPerArtist}) 로
 * 1회 적재량을 통제한다 (admin endpoint 단계적 트리거). 외부 호출이 많으므로 운영자가 점진 상향한다.
 */
@Component
public class CatalogBrowseImportCommand {

    static final String ARTISTS_SEED_PATH = "korean-artists-seed.json";

    private static final Logger LOG = LoggerFactory.getLogger(CatalogBrowseImportCommand.class);

    /** 마지막 browse 임포트 완료 시각 — in-memory atomic. 재기동 시 null. 운영 가시성용. dryRun 은 갱신하지 않는다. */
    private static final AtomicReference<Instant> LAST_IMPORT_COMPLETED_AT = new AtomicReference<>();

    private final SongRepository songRepository;
    private final CatalogBrowseClient browseClient;
    private final CatalogBrowseProperties properties;

    public CatalogBrowseImportCommand(
            final SongRepository songRepository,
            final CatalogBrowseClient browseClient,
            final CatalogBrowseProperties properties) {
        this.songRepository = songRepository;
        this.browseClient = browseClient;
        this.properties = properties;
    }

    /**
     * 아티스트 시드를 browse 임포트한다 — admin endpoint / 테스트 공용 진입점.
     *
     * @param artistNames            한국 대중가요 아티스트 표기 목록 (수기 큐레이션 시드)
     * @param maxArtists             이번 사이클 처리 아티스트 수 상한 (1 이상)
     * @param maxRecordingsPerArtist 아티스트당 적재 recording 수 상한 (1 이상)
     * @param dryRun                 true 면 DB write 없이 적재 가능 후보만 집계 (운영자 사전 점검용)
     * @return 처리 요약
     */
    public BrowseSummary runBrowseImport(
            final List<String> artistNames,
            final int maxArtists,
            final int maxRecordingsPerArtist,
            final boolean dryRun) {
        Objects.requireNonNull(artistNames, "artistNames must not be null");
        final long startedAt = System.currentTimeMillis();
        final int pageSize = properties.pageSize();
        final Set<String> seenMbIds = new HashSet<>();
        final Set<String> seenIsrcs = new HashSet<>();
        final Set<String> seenTitleArtists = new HashSet<>();
        int artistsProcessed = 0;
        int recordingsSeen = 0;
        int inserted = 0;
        int skipped = 0;
        int failed = 0;
        boolean aborted = false;
        outer:
        for (final String artistName : artistNames.stream().limit(Math.max(0, maxArtists)).toList()) {
            if (artistName == null || artistName.isBlank()) {
                continue;
            }
            artistsProcessed++;
            int importedForArtist = 0;
            int offset = 0;
            while (importedForArtist < maxRecordingsPerArtist) {
                final List<BrowsedRecording> page;
                try {
                    page = browseClient.browseByArtist(artistName, pageSize, offset);
                } catch (final CatalogBrowseRateLimitException e) {
                    LOG.warn("catalog browse aborted — rate limit. artistsProcessed={} reason={}",
                            artistsProcessed, e.getMessage());
                    aborted = true;
                    break outer;
                }
                if (page.isEmpty()) {
                    break;
                }
                for (final BrowsedRecording recording : page) {
                    if (importedForArtist >= maxRecordingsPerArtist) {
                        break;
                    }
                    recordingsSeen++;
                    final UpsertOutcome outcome = upsert(
                            recording, dryRun, seenMbIds, seenIsrcs, seenTitleArtists);
                    switch (outcome) {
                        case INSERTED -> {
                            inserted++;
                            importedForArtist++;
                        }
                        case SKIPPED -> skipped++;
                        case FAILED -> failed++;
                        default -> {
                        }
                    }
                }
                if (page.size() < pageSize) {
                    break;
                }
                offset += pageSize;
            }
        }
        final long elapsedMs = System.currentTimeMillis() - startedAt;
        LOG.info(
                "catalog browse import done dryRun={} artistsProcessed={} recordingsSeen={} inserted={} "
                        + "skipped={} failed={} aborted={} elapsedMs={}",
                dryRun, artistsProcessed, recordingsSeen, inserted, skipped, failed, aborted, elapsedMs);
        if (!dryRun) {
            LAST_IMPORT_COMPLETED_AT.set(Instant.now());
        }
        return new BrowseSummary(
                artistsProcessed, recordingsSeen, inserted, skipped, failed, aborted, elapsedMs);
    }

    private UpsertOutcome upsert(
            final BrowsedRecording recording,
            final boolean dryRun,
            final Set<String> seenMbIds,
            final Set<String> seenIsrcs,
            final Set<String> seenTitleArtists) {
        final String title = recording.title();
        final String artist = recording.artist();
        if (title == null || title.isBlank() || artist == null || artist.isBlank()) {
            return UpsertOutcome.FAILED;
        }
        if (isDuplicate(recording, title, artist, seenMbIds, seenIsrcs, seenTitleArtists)) {
            return UpsertOutcome.SKIPPED;
        }
        rememberSeen(recording, title, artist, seenMbIds, seenIsrcs, seenTitleArtists);
        if (dryRun) {
            return UpsertOutcome.INSERTED;
        }
        try {
            songRepository.save(buildImportedSong(recording));
            return UpsertOutcome.INSERTED;
        } catch (final RuntimeException e) {
            LOG.warn("catalog browse save failed title={} reason={}", title, e.getMessage());
            return UpsertOutcome.FAILED;
        }
    }

    private boolean isDuplicate(
            final BrowsedRecording recording,
            final String title,
            final String artist,
            final Set<String> seenMbIds,
            final Set<String> seenIsrcs,
            final Set<String> seenTitleArtists) {
        final String mbId = recording.mbId();
        if (mbId != null && !mbId.isBlank()
                && (seenMbIds.contains(mbId) || songRepository.findByMbId(mbId).isPresent())) {
            return true;
        }
        final String isrc = recording.isrc();
        if (isrc != null && !isrc.isBlank()
                && (seenIsrcs.contains(isrc) || songRepository.findByIsrc(isrc).isPresent())) {
            return true;
        }
        final String titleArtistKey = titleArtistKey(title, artist);
        return seenTitleArtists.contains(titleArtistKey)
                || songRepository.findByTitleAndArtist(title, artist).isPresent();
    }

    private static void rememberSeen(
            final BrowsedRecording recording,
            final String title,
            final String artist,
            final Set<String> seenMbIds,
            final Set<String> seenIsrcs,
            final Set<String> seenTitleArtists) {
        if (recording.mbId() != null && !recording.mbId().isBlank()) {
            seenMbIds.add(recording.mbId());
        }
        if (recording.isrc() != null && !recording.isrc().isBlank()) {
            seenIsrcs.add(recording.isrc());
        }
        seenTitleArtists.add(titleArtistKey(title, artist));
    }

    private static String titleArtistKey(final String title, final String artist) {
        return title.toLowerCase(Locale.ROOT) + ' ' + artist.toLowerCase(Locale.ROOT);
    }

    /**
     * 임포트 곡을 만든다 — {@link MetadataSource#EXTERNAL_API}, key {@link MusicalKey#UNKNOWN}(외부 미제공),
     * 음역대/bpm 미설정(자체 분석 후속), 낮은 confidence(메타만 → 음역 미상). MBID/ISRC/연도/장르는 있으면 채운다.
     */
    private Song buildImportedSong(final BrowsedRecording recording) {
        return Song.builder()
                .title(recording.title())
                .artist(recording.artist())
                .releaseYear(recording.releaseYear())
                .keyOriginal(MusicalKey.UNKNOWN)
                .genre(recording.genre())
                .metadataSource(MetadataSource.EXTERNAL_API)
                .isrc(recording.isrc())
                .mbId(recording.mbId())
                .metadataConfidence(properties.importConfidence())
                .build();
    }

    /** 마지막 browse 임포트 완료 시각 — 미실행 시 {@code null}. 재기동 시 초기화. dryRun 은 갱신하지 않는다. */
    public static Instant getLastImportCompletedAt() {
        return LAST_IMPORT_COMPLETED_AT.get();
    }

    private enum UpsertOutcome {
        INSERTED,
        SKIPPED,
        FAILED
    }

    /**
     * @param artistsProcessed browse 시도한 아티스트 수
     * @param recordingsSeen   외부에서 받아 검토한 recording 총 수
     * @param inserted         신규 적재(dryRun 시 적재 가능) 곡 수
     * @param skipped          멱등 중복으로 건너뛴 곡 수
     * @param failed           제목/아티스트 누락·저장 오류로 적재 못 한 곡 수
     * @param aborted          503 rate limit 으로 batch 가 중단됐는지
     * @param elapsedMs        전체 소요 (ms)
     */
    public record BrowseSummary(
            int artistsProcessed,
            int recordingsSeen,
            int inserted,
            int skipped,
            int failed,
            boolean aborted,
            long elapsedMs
    ) {
    }
}
