package com.mobruji.song.application.catalogimport;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

/**
 * 곡 카탈로그 메타-only 임포트 배치 — spec {@code song-catalog-expansion.md} PR 2 (#1496).
 *
 * <p>운영자가 차트를 수기 참고해 정리한 (title, artist) 후보 목록
 * ({@code classpath:/song-import-candidates.json}) 을 입력으로, 각 곡을 MusicBrainz(CC0) 에 질의해
 * 메타데이터(연도/장르/ISRC)만 채워 {@link Song} 으로 upsert 한다. 음역대/key/tempo 는 채우지 않는다 —
 * 외부 출처가 제공하지 않으며, 자체 분석({@code song-self-analysis-pipeline.md}, #1490) 큐가 후속한다 (spec §5-0).
 *
 * <p>멱등성 (spec §3) — 동일 (title, artist) 또는 동일 ISRC 가 이미 있으면 skip 한다. 운영자가 수정한 값은
 * 절대 덮지 않는다 (메타 enrichment 는 본 스캐폴드 범위 밖 — {@code musicbrainz-integration} backfill 책임).
 *
 * <p>곡 단위 자체 트랜잭션 (한 곡 실패가 다음 곡을 막지 않는 의도된 격리). 곡 사이 {@code throttle} 만큼
 * sleep — MusicBrainz 1 req/s rate limit 보수적 준수.
 *
 * <p>실행 방법 (수동 trigger, 운영 안전):
 * <pre>
 * ./gradlew bootRun --args='--spring.profiles.active=local --mobruji.import-catalog=true'
 * </pre>
 *
 * <p>인자 미지정 시 no-op — 평시 부팅에 영향 없음. {@code test} 프로파일은 Bean 미등록으로 통합 테스트 영향 없음.
 *
 * <p>결정성 영향 없음 — 임포트 곡은 음역대 미설정이라 추천 점수 산식 입력이 채워지지 않는다 (spec §6 보호 영역 사유).
 */
@Component
@Profile("!test")
public class MetadataOnlyImportCommand implements ApplicationRunner {

    /** ApplicationArguments 에서 인식할 옵션 키. {@code --mobruji.import-catalog=true} */
    static final String OPTION_KEY = "mobruji.import-catalog";

    static final String CANDIDATES_PATH = "song-import-candidates.json";

    private static final Logger LOG = LoggerFactory.getLogger(MetadataOnlyImportCommand.class);

    /** 마지막 임포트 완료 시각 — in-memory atomic. 재기동 시 null. 운영 가시성용. */
    private static final AtomicReference<Instant> LAST_IMPORT_COMPLETED_AT = new AtomicReference<>();

    private final SongRepository songRepository;
    private final SongMetadataLookupClient lookupClient;
    private final CatalogImportProperties properties;
    private final ObjectMapper objectMapper;

    public MetadataOnlyImportCommand(
            final SongRepository songRepository,
            final SongMetadataLookupClient lookupClient,
            final CatalogImportProperties properties,
            final ObjectMapper objectMapper) {
        this.songRepository = songRepository;
        this.lookupClient = lookupClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(final ApplicationArguments args) throws IOException {
        if (!isOptionTrue(args)) {
            return;
        }
        runImport(loadCandidates());
    }

    /**
     * classpath 후보 JSON 을 읽는다. 파일 부재 시 빈 목록 (스캐폴드 단계 — 후보 목록은 후속 PR 에서 채운다).
     */
    List<CandidateEntry> loadCandidates() throws IOException {
        final ClassPathResource resource = new ClassPathResource(CANDIDATES_PATH);
        if (!resource.exists()) {
            LOG.warn("catalog import candidates not found at classpath:/{}, skipping", CANDIDATES_PATH);
            return List.of();
        }
        try (InputStream inputStream = resource.getInputStream()) {
            return objectMapper.readValue(
                    inputStream,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, CandidateEntry.class));
        }
    }

    /**
     * 후보 목록을 메타-only 임포트한다 — 테스트/CLI 재사용 진입점.
     *
     * @return 처리 요약
     */
    ImportSummary runImport(final List<CandidateEntry> candidates) {
        int inserted = 0;
        int skipped = 0;
        int insertedWithoutMetadata = 0;
        int failed = 0;
        final Duration throttle = properties.throttle();
        for (final CandidateEntry candidate : candidates) {
            Objects.requireNonNull(candidate, "candidate must not be null");
            if (candidate.title() == null || candidate.title().isBlank()
                    || candidate.artist() == null || candidate.artist().isBlank()) {
                LOG.warn("catalog import skip blank candidate");
                failed++;
                continue;
            }
            if (songRepository.findByTitleAndArtist(candidate.title(), candidate.artist()).isPresent()) {
                skipped++;
                continue;
            }
            final Optional<ImportedSongMetadata> metadata;
            try {
                metadata = lookupClient.lookupMetadata(candidate.title(), candidate.artist());
            } catch (final RuntimeException e) {
                // 클라이언트가 graceful 보장하지만 방어. 곡 단위 격리.
                LOG.warn(
                        "catalog import lookup error title={} reason={}",
                        candidate.title(), e.getMessage());
                failed++;
                throttle(throttle);
                continue;
            }
            final String isrc = metadata.map(ImportedSongMetadata::isrc).orElse(null);
            if (isrc != null && songRepository.findByIsrc(isrc).isPresent()) {
                skipped++;
                throttle(throttle);
                continue;
            }
            try {
                songRepository.save(buildImportedSong(candidate, metadata.orElse(null)));
                if (metadata.isEmpty()) {
                    insertedWithoutMetadata++;
                } else {
                    inserted++;
                }
            } catch (final RuntimeException e) {
                LOG.warn("catalog import save failed title={} reason={}", candidate.title(), e.getMessage());
                failed++;
            }
            throttle(throttle);
        }
        LOG.info(
                "catalog import done total={} inserted={} insertedWithoutMetadata={} skipped={} failed={}",
                candidates.size(), inserted, insertedWithoutMetadata, skipped, failed);
        LAST_IMPORT_COMPLETED_AT.set(Instant.now());
        return new ImportSummary(candidates.size(), inserted, insertedWithoutMetadata, skipped, failed);
    }

    /**
     * 임포트 곡을 만든다 — {@link MetadataSource#EXTERNAL_API}, key {@link MusicalKey#UNKNOWN}(외부 미제공),
     * 음역대/bpm 미설정(자체 분석 후속), 낮은 confidence(메타만 → 음역 미상). 메타 미매칭이면 연도/장르/ISRC 는 null.
     */
    private Song buildImportedSong(final CandidateEntry candidate, final ImportedSongMetadata metadata) {
        return Song.builder()
                .title(candidate.title())
                .artist(candidate.artist())
                .releaseYear(metadata != null ? metadata.releaseYear() : null)
                .keyOriginal(MusicalKey.UNKNOWN)
                .genre(metadata != null ? metadata.genre() : null)
                .metadataSource(MetadataSource.EXTERNAL_API)
                .isrc(metadata != null ? metadata.isrc() : null)
                .metadataConfidence(properties.importConfidence())
                .build();
    }

    /** 마지막 임포트 완료 시각 — 미실행 시 {@code null}. 재기동 시 초기화된다. */
    public static Instant getLastImportCompletedAt() {
        return LAST_IMPORT_COMPLETED_AT.get();
    }

    private static void throttle(final Duration throttle) {
        if (throttle == null || throttle.isZero() || throttle.isNegative()) {
            return;
        }
        try {
            TimeUnit.MILLISECONDS.sleep(throttle.toMillis());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isOptionTrue(final ApplicationArguments args) {
        if (args == null || !args.containsOption(OPTION_KEY)) {
            return false;
        }
        final List<String> values = args.getOptionValues(OPTION_KEY);
        if (values == null || values.isEmpty()) {
            return true;
        }
        return "true".equalsIgnoreCase(values.get(values.size() - 1));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CandidateEntry(
            String title,
            String artist
    ) {
    }

    record ImportSummary(
            int total,
            int inserted,
            int insertedWithoutMetadata,
            int skipped,
            int failed
    ) {
    }
}
