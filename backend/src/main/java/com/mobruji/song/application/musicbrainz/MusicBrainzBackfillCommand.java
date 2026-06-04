package com.mobruji.song.application.musicbrainz;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * MusicBrainz Recording 매칭 backfill batch — spec {@code musicbrainz-integration.md} PR B (#268).
 *
 * <p>{@link SongRepository#findMissingMbId()} 로 {@code mbId IS NULL} 곡만 selective 조회(저신뢰도 우선),
 * 곡마다 {@link MusicBrainzClient#searchTopRecording(String, String)} → score 임계 판단 → 채택 시
 * {@link Song#backfillFromMusicBrainz(String, String, double)} 적용. 음역대/key/tempo 는 채우지 않으므로
 * 추천 점수 산식 입력이 바뀌지 않는다 (결정성 회귀 없음).
 *
 * <p>멱등성 (spec §3) — 이미 {@code mbId} 가 있는 곡은 query 단계에서 제외되고, 도메인 메서드가 한 번 더 보호한다.
 * mbId/isrc UNIQUE 충돌은 적용 전에 사전 lookup 으로 회피한다 — 같은 recording 이 두 곡에 매칭되거나
 * 운영자값을 덮어쓰는 사고를 막는다.
 *
 * <p>rate limit (spec §5-4) — 503 이 backoff 를 모두 소진하면 {@link MusicBrainzRateLimitException} 으로 batch
 * 전체를 즉시 중단한다(rate limit 침해 위험). 그 외 곡 단위 실패(무매칭/저score/timeout)는 통계만 누적하고 다음
 * 곡으로 진행한다 (곡 단위 자체 트랜잭션 격리).
 *
 * <p>관측성 — 곡 outcome 마다 {@code mobruji.external.musicbrainz.request{outcome=...}} counter +1.
 * observability-baseline.md §5-3 표 단일 진실.
 */
@Component
public class MusicBrainzBackfillCommand {

    /**
     * Micrometer counter 이름 — observability-baseline.md §5-3 표 단일 진실. {@code outcome} 태그:
     * {@code success}/{@code lowscore}/{@code notfound}/{@code error}/{@code ratelimited}.
     */
    static final String METRIC_REQUEST = "mobruji.external.musicbrainz.request";

    private static final Logger LOG = LoggerFactory.getLogger(MusicBrainzBackfillCommand.class);

    /** 마지막 backfill 완료 시각 — in-memory atomic. 재기동 시 null. 운영 가시성용. */
    private static final AtomicReference<Instant> LAST_BACKFILL_COMPLETED_AT = new AtomicReference<>();

    private final SongRepository songRepository;
    private final MusicBrainzClient musicBrainzClient;
    private final MusicBrainzProperties properties;
    private final MeterRegistry meterRegistry;

    public MusicBrainzBackfillCommand(
            final SongRepository songRepository,
            final MusicBrainzClient musicBrainzClient,
            final MusicBrainzProperties properties,
            final MeterRegistry meterRegistry) {
        this.songRepository = songRepository;
        this.musicBrainzClient = musicBrainzClient;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 기본 설정(batch-size / min-score)으로 실제 영속 backfill 을 실행한다 — 정기 스케줄러 진입점.
     */
    BackfillSummary runBackfill() {
        return runBackfill(false, properties.backfill().batchSize(), properties.minScore());
    }

    /**
     * backfill 을 실행한다 — admin endpoint / 스케줄러 / 테스트 공용 진입점.
     *
     * @param dryRun   true 면 DB write 없이 매칭 후보 집계만 (운영자 사전 점검용).
     * @param maxBatch 이번 사이클 처리 곡 수 상한 (1 이상). {@code mbId IS NULL} 곡을 저신뢰도 순으로 잘라 처리.
     * @param minScore 채택 score 임계 (0~100). top-hit score 가 미만이면 lowscore skip.
     * @return 처리 요약
     */
    public BackfillSummary runBackfill(final boolean dryRun, final int maxBatch, final int minScore) {
        final long startedAt = System.currentTimeMillis();
        final List<Song> targets = songRepository.findMissingMbId().stream()
                .limit(Math.max(0, maxBatch))
                .toList();
        int processed = 0;
        int matched = 0;
        int lowScore = 0;
        int notFound = 0;
        int failed = 0;
        boolean aborted = false;
        for (final Song song : targets) {
            Objects.requireNonNull(song, "song must not be null");
            processed++;
            try {
                final Optional<MusicBrainzMatch> match = musicBrainzClient.searchTopRecording(song.getTitle(), song
                        .getArtist());
                if (match.isEmpty()) {
                    notFound++;
                    count("notfound");
                    continue;
                }
                if (match.get().score() < minScore) {
                    lowScore++;
                    count("lowscore");
                    continue;
                }
                if (applyMatch(song, match.get(), dryRun)) {
                    matched++;
                    count("success");
                } else {
                    failed++;
                    count("error");
                }
            } catch (final MusicBrainzRateLimitException e) {
                LOG.warn("musicbrainz backfill aborted — rate limit. processed={} reason={}",
                        processed, e.getMessage());
                count("ratelimited");
                aborted = true;
                break;
            } catch (final RuntimeException e) {
                LOG.warn("musicbrainz backfill song error songId={} reason={}", song.getId(), e.getMessage());
                failed++;
                count("error");
            }
        }
        final long elapsedMs = System.currentTimeMillis() - startedAt;
        LOG.info(
                "musicbrainz backfill done dryRun={} processed={} matched={} lowScore={} notFound={} "
                        + "failed={} aborted={} elapsedMs={}",
                dryRun, processed, matched, lowScore, notFound, failed, aborted, elapsedMs);
        if (!dryRun) {
            LAST_BACKFILL_COMPLETED_AT.set(Instant.now());
        }
        return new BackfillSummary(processed, matched, lowScore, notFound, failed, aborted, elapsedMs);
    }

    /**
     * 채택된 매칭을 적용한다. UNIQUE 충돌(같은 mbId 가 다른 곡에 이미 존재 / 같은 isrc 가 다른 곡에 이미 존재)은
     * 사전 lookup 으로 회피한다. dryRun 이면 실제 적용 없이 적용 가능 여부만 true 로 본다.
     *
     * @return 적용(또는 dryRun 시 적용 가능)됐으면 true, mbId 충돌로 건너뛰면 false
     */
    private boolean applyMatch(final Song song, final MusicBrainzMatch match, final boolean dryRun) {
        final String mbId = match.mbId();
        final boolean mbIdTakenByOther = songRepository.findByMbId(mbId)
                .filter(other -> !Objects.equals(other.getId(), song.getId()))
                .isPresent();
        if (mbIdTakenByOther) {
            LOG.warn("musicbrainz backfill mbId conflict songId={} — skip", song.getId());
            return false;
        }
        final String isrc = resolveIsrc(song, match);
        if (dryRun) {
            return true;
        }
        final boolean changed = song.backfillFromMusicBrainz(mbId, isrc, match.confidence());
        if (changed) {
            songRepository.save(song);
        }
        return changed;
    }

    /**
     * 적용할 ISRC 를 결정한다 — 검색 응답에 없으면 상세 lookup 으로 보강하고, 다른 곡이 이미 그 ISRC 를 보유하면
     * (UNIQUE 충돌 회피) null 로 떨어뜨려 mbId 만 채운다.
     */
    private String resolveIsrc(final Song song, final MusicBrainzMatch match) {
        String isrc = match.isrc();
        if (isrc == null) {
            isrc = musicBrainzClient.lookupIsrc(match.mbId()).orElse(null);
        }
        if (isrc == null) {
            return null;
        }
        final boolean isrcTakenByOther = songRepository.findByIsrc(isrc)
                .filter(other -> !Objects.equals(other.getId(), song.getId()))
                .isPresent();
        return isrcTakenByOther ? null : isrc;
    }

    private void count(final String outcome) {
        Counter.builder(METRIC_REQUEST)
                .description("MusicBrainz backfill 곡 outcome 별 호출 집계")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

    /** 마지막 backfill 완료 시각 — 미실행 시 {@code null}. 재기동 시 초기화된다. dryRun 은 갱신하지 않는다. */
    public static Instant getLastBackfillCompletedAt() {
        return LAST_BACKFILL_COMPLETED_AT.get();
    }

    /**
     * @param processed 조회·시도한 곡 수
     * @param matched   매칭 채택(dryRun 시 채택 가능) 곡 수
     * @param lowScore  top-hit score 가 임계 미만이라 skip 한 곡 수
     * @param notFound  검색 무매칭 곡 수
     * @param failed    오류/충돌로 적용 못 한 곡 수
     * @param aborted   503 rate limit 으로 batch 가 중단됐는지
     * @param elapsedMs 전체 소요 (ms)
     */
    public record BackfillSummary(
            int processed,
            int matched,
            int lowScore,
            int notFound,
            int failed,
            boolean aborted,
            long elapsedMs
    ) {
    }
}
