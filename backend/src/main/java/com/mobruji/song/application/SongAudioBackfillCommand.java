package com.mobruji.song.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.mobruji.song.domain.AudioAnalysisFailedException;
import com.mobruji.song.domain.AudioAnalysisResult;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 시드 30곡(또는 DB 전체)의 lowMidi/highMidi 를 Python audio analysis tool 산출값으로 backfill 하는 일회성 batch.
 *
 * <p>곡 단위 자체 트랜잭션 (한 곡 실패가 다음 곡 막지 않는 의도된 격리). 이슈 #863 — {@code @Transactional}
 * 부재는 의도된 설계로, 곡별 save 호출이 Spring 의 기본 트랜잭션 경계 1곡 ↔ 1 commit 로 동작한다. SongAnalysis
 * 도입 시 트랜잭션 경계 재설계 필수 — spec {@code docs/features/audio-tooling-bootstrap.md} §10-7 cross-ref.
 *
 * <p>spec: {@code docs/features/audio-tooling-bootstrap.md} PR C — 수기 시드의 음역대 정확도를 audio 분석으로
 * 끌어올린다. 추천 알고리즘 코드는 그대로이고 입력 데이터만 정확해지므로 결정성 회귀는 없다.
 *
 * <p>실행 방법(수동 trigger, 운영 안전):
 * <pre>
 * ./gradlew bootRun --args='--spring.profiles.active=local --mobruji.backfill-audio=true'
 * </pre>
 *
 * <p>소량 검증 배치(이슈 #1716) — {@code --mobruji.backfill-audio.limit=N} 추가 시 전체 대신 backfill 후보
 * (미분석/신규 곡) 중 id 순 앞 N곡만 분석한다. 신규 임포트 곡 음역대 backfill 정확도를 소량 표본으로 먼저 검증:
 * <pre>
 * ./gradlew bootRun --args='--spring.profiles.active=local --mobruji.backfill-audio=true
 * --mobruji.backfill-audio.limit=5'
 * </pre>
 *
 * <p>인자({@link ApplicationArguments}) 미지정 시 no-op — 평시 부팅에 영향 없음. {@code test} 프로파일에서는
 * Spring Bean 자체를 등록하지 않아 통합 테스트가 영향받지 않는다.
 *
 * <p>동작:
 * <ol>
 * <li>{@link SongRepository#findAll()} 전체 곡을 순회.</li>
 * <li>각 곡에 대해 {@link AudioAnalysisRunner#analyzeByMetadata(String, String)} 60s timeout 호출.</li>
 * <li>결과를 {@link Song#backfillFromAudioAnalysis(AudioAnalysisResult, double)} 에 위임 — confidence
 * 임계(기본 0.6) 통과 시 update, 아니면 보존.</li>
 * <li>곡 단위 실패(timeout/parse error/Python 예외)는 로그 + 다음 곡으로 진행. 전체 중단하지 않는다.</li>
 * <li>완료 시 요약 로그 1줄.</li>
 * </ol>
 *
 * <p>임시 audio 파일은 Python 측({@code analyze.py})에서 분석 후 즉시 삭제 — ADR 0006.
 */
@Component
@Profile("!test")
public class SongAudioBackfillCommand implements ApplicationRunner {

    /** ApplicationArguments 에서 인식할 옵션 키. {@code --mobruji.backfill-audio=true} */
    static final String OPTION_KEY = "mobruji.backfill-audio";

    /**
     * 소량 검증 배치 옵션 키 — {@code --mobruji.backfill-audio.limit=N}. 지정 시 전체({@code findAll}) 대신
     * backfill 후보({@link SongRepository#findCandidatesForBackfill(double)} = 미분석/신규 곡)를 id 순 앞에서 N곡만
     * 처리한다. 이슈 #1716 — 신규 임포트 곡 음역대 backfill 을 무분별 대량이 아닌 소량(5~10곡) 검증 배치로 먼저 돌린다.
     * 옵션 미지정 시 기존 전체 backfill 동작 그대로 유지.
     */
    static final String OPTION_LIMIT_KEY = "mobruji.backfill-audio.limit";

    /**
     * 적용 임계 confidence — 작업 지시 기본 0.6. 임계 미달은 수기 값을 보존한다.
     */
    static final double DEFAULT_CONFIDENCE_THRESHOLD = 0.6;

    private static final Logger LOG = LoggerFactory.getLogger(SongAudioBackfillCommand.class);

    /**
     * 마지막으로 backfill batch 가 완료된 시각. in-memory atomic — admin 통계 API
     * ({@code GET /api/v1/songs/stats}) 가 noop 으로 조회한다. KVStore/DB 미사용 — 재기동 시 null.
     *
     * <p>spec rev 14 후속(#208/#212): 통계 화면에서 "최근 batch 시각" 노출용. 정확한 historical
     * audit 가 필요해지면 별도 테이블로 승격한다 (오픈 이슈).
     */
    private static final AtomicReference<Instant> LAST_BACKFILL_COMPLETED_AT = new AtomicReference<>();

    /**
     * Micrometer metric 이름 — spec {@code docs/features/observability-baseline.md} §5-3 표 단일 진실.
     * 본 클래스 외부에서 metric 이름을 참조하는 코드(통합 테스트, 대시보드, 알림 룰) 가 본 상수를 우선 참조한다.
     */
    static final String METRIC_BACKFILL_REQUESTED = "mobruji.song.audio.backfill.requested";

    static final String METRIC_BACKFILL_SUCCESS = "mobruji.song.audio.backfill.success";

    static final String METRIC_BACKFILL_FAILED = "mobruji.song.audio.backfill.failed";

    /**
     * 실패 사유 라벨 enum — observability-baseline §5-7 화이트리스트 ({@code reason}: python/io/parse/timeout) 와 정합.
     * 본 구현은 catch 분기마다 고정 상수 reason 만 사용 — 동적 문자열은 카디널리티 폭발 방지를 위해 금지.
     */
    static final String REASON_TIMEOUT = "timeout";

    static final String REASON_SPAWN_ERROR = "spawn_error";

    static final String REASON_JSON_PARSE = "json_parse";

    static final String REASON_SONG_APPLY = "song_apply";

    static final String REASON_OTHER = "other";

    private final SongRepository songRepository;
    private final AudioAnalysisRunner audioAnalysisRunner;
    private final MeterRegistry meterRegistry;
    private final Counter backfillRequestedCounter;
    private final Counter backfillSuccessCounter;

    public SongAudioBackfillCommand(
            final SongRepository songRepository,
            final AudioAnalysisRunner audioAnalysisRunner,
            final MeterRegistry meterRegistry) {
        this.songRepository = songRepository;
        this.audioAnalysisRunner = audioAnalysisRunner;
        this.meterRegistry = meterRegistry;
        this.backfillRequestedCounter = Counter.builder(METRIC_BACKFILL_REQUESTED)
                .description("audio backfill batch trigger 횟수 (수동 + 스케줄)")
                .register(meterRegistry);
        this.backfillSuccessCounter = Counter.builder(METRIC_BACKFILL_SUCCESS)
                .description("audio backfill 곡 단위 성공 횟수 (DB 적용/임계 미달 무관)")
                .register(meterRegistry);
    }

    @Override
    public void run(final ApplicationArguments args) {
        if (!isOptionTrue(args)) {
            return;
        }
        final OptionalInt limit = parseLimit(args);
        if (limit.isPresent()) {
            runBoundedCandidateBackfill(limit.getAsInt(), DEFAULT_CONFIDENCE_THRESHOLD);
        } else {
            runBackfill(DEFAULT_CONFIDENCE_THRESHOLD);
        }
    }

    /**
     * 검증 배치 진입점 — backfill 후보(미분석/신규 곡) 중 id 순 앞에서 {@code limit} 곡만 분석한다. 신규 임포트 곡의
     * 음역대 backfill 정확도를 소량 표본으로 먼저 검증하기 위한 경로(이슈 #1716). 전체({@code findAll})를 건드리지 않아
     * 이미 분석된 곡 재분석/대량 처리 비용을 피한다.
     *
     * @param limit               분석할 후보 곡 상한 (양수)
     * @param confidenceThreshold 적용 임계 confidence
     * @return 처리 요약
     */
    BackfillSummary runBoundedCandidateBackfill(final int limit, final double confidenceThreshold) {
        final List<Song> candidates = songRepository.findCandidatesForBackfill(confidenceThreshold);
        final List<Song> selected = candidates.stream().limit(limit).toList();
        LOG.info(
                "audio backfill bounded validation batch: candidates={} limit={} selected={}",
                candidates.size(), limit, selected.size());
        return runBackfill(selected, confidenceThreshold);
    }

    /**
     * 테스트/CLI 재사용을 위한 진입점. 임계값을 명시 전달해 단위 검증에 쓴다.
     *
     * @return 처리 요약
     */
    BackfillSummary runBackfill(final double confidenceThreshold) {
        return runBackfill(songRepository.findAll(), confidenceThreshold);
    }

    /**
     * 임의 곡 집합에 대해 backfill 실행 — 정기 batch ({@code AudioAnalysisScheduledBackfill}) 가 분석 대상
     * 곡을 selective 하게 결정해 호출할 수 있도록 노출한다.
     */
    BackfillSummary runBackfill(final List<Song> songs, final double confidenceThreshold) {
        backfillRequestedCounter.increment();
        int analyzed = 0;
        int successful = 0;
        int updated = 0;
        int skippedLowConfidence = 0;
        int skippedImplausibleRange = 0;
        int failed = 0;
        for (final Song song : songs) {
            Objects.requireNonNull(song, "song must not be null");
            analyzed++;
            final AudioAnalysisResult result;
            try {
                result = audioAnalysisRunner.analyzeByMetadata(song.getTitle(), song.getArtist());
            } catch (final AudioAnalysisFailedException e) {
                LOG.warn(
                        "audio backfill failed songId={} title={} reason={}",
                        song.getId(), song.getTitle(), e.getMessage());
                incrementFailed(classifyFailure(e));
                failed++;
                continue;
            } catch (final RuntimeException e) {
                // ProcessBuilder/IO 계열 unchecked 예외도 곡 단위로 격리 — 전체 batch 중단 회피.
                LOG.warn(
                        "audio backfill error songId={} title={} reason={}",
                        song.getId(), song.getTitle(), e.getMessage());
                incrementFailed(REASON_OTHER);
                failed++;
                continue;
            }
            successful++;
            backfillSuccessCounter.increment();
            // 합리성 가드(#1725) — confidence 임계와 무관하게 비합리 음역대(반주 저음 오검출·옥타브 폴딩)는
            // 추천 풀 진입 전 거부한다. 도메인(backfillFromAudioAnalysis)도 동일 가드를 갖지만, 거부 사유를
            // 구분해 집계하기 위해 여기서 선판정한다.
            if (!result.isVocalRangePlausible()) {
                LOG.warn(
                        "audio backfill skipped implausible range songId={} title={} lowMidi={} highMidi={}",
                        song.getId(), song.getTitle(), result.lowMidi(), result.highMidi());
                skippedImplausibleRange++;
                continue;
            }
            final boolean changed;
            try {
                changed = song.backfillFromAudioAnalysis(result, confidenceThreshold);
            } catch (final RuntimeException e) {
                // backfill 자체에서 도메인 검증 실패 (예: lowMidi>highMidi) — 곡 단위 격리.
                LOG.warn(
                        "audio backfill apply failed songId={} title={} reason={}",
                        song.getId(), song.getTitle(), e.getMessage());
                incrementFailed(REASON_SONG_APPLY);
                failed++;
                continue;
            }
            if (changed) {
                songRepository.save(song);
                updated++;
            } else {
                skippedLowConfidence++;
            }
        }
        LOG.info(
                "audio backfill done analyzed={} successful={} updated={} skipped_low_confidence={} "
                        + "skipped_implausible_range={} failed={}",
                analyzed, successful, updated, skippedLowConfidence, skippedImplausibleRange, failed);
        LAST_BACKFILL_COMPLETED_AT.set(Instant.now());
        return new BackfillSummary(
                analyzed, successful, updated, skippedLowConfidence, skippedImplausibleRange, failed);
    }

    /**
     * {@link AudioAnalysisFailedException} 메시지를 사전 정의 reason enum 로 매핑한다. 메시지 본문은 카디널리티 폭발 우려로
     * 라벨 직접 사용 금지 — observability-baseline §5-7 화이트리스트 정합. timeout / spawn / parse 외 메시지는 모두
     * {@code other} 로 흡수.
     */
    private static String classifyFailure(final AudioAnalysisFailedException e) {
        final String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("timeout")) {
            return REASON_TIMEOUT;
        }
        if (message.contains("spawn") || message.contains("Cannot run program")
                || message.contains("not found")) {
            return REASON_SPAWN_ERROR;
        }
        if (message.contains("parse") || message.contains("JSON") || message.contains("missing required field")
                || message.contains("empty stdout")) {
            return REASON_JSON_PARSE;
        }
        return REASON_OTHER;
    }

    private void incrementFailed(final String reason) {
        meterRegistry.counter(METRIC_BACKFILL_FAILED, "reason", reason).increment();
    }

    /**
     * 마지막 backfill 완료 시각 — 아직 한 번도 실행되지 않았으면 {@code null}. 재기동 시 초기화된다.
     */
    public static Instant getLastBackfillCompletedAt() {
        return LAST_BACKFILL_COMPLETED_AT.get();
    }

    /**
     * {@code --mobruji.backfill-audio.limit=N} 파싱 — 미지정/빈 값이면 {@link OptionalInt#empty()}.
     * 양의 정수만 허용하며 0/음수/비정수는 잘못된 수동 trigger 이므로 fail-fast 한다.
     */
    private static OptionalInt parseLimit(final ApplicationArguments args) {
        if (args == null || !args.containsOption(OPTION_LIMIT_KEY)) {
            return OptionalInt.empty();
        }
        final List<String> values = args.getOptionValues(OPTION_LIMIT_KEY);
        if (values == null || values.isEmpty()) {
            return OptionalInt.empty();
        }
        final String raw = values.get(values.size() - 1).trim();
        final int parsed;
        try {
            parsed = Integer.parseInt(raw);
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException(
                    "--" + OPTION_LIMIT_KEY + " 는 양의 정수여야 합니다: " + raw, e);
        }
        if (parsed <= 0) {
            throw new IllegalArgumentException(
                    "--" + OPTION_LIMIT_KEY + " 는 양의 정수여야 합니다: " + parsed);
        }
        return OptionalInt.of(parsed);
    }

    private static boolean isOptionTrue(final ApplicationArguments args) {
        if (args == null || !args.containsOption(OPTION_KEY)) {
            return false;
        }
        final List<String> values = args.getOptionValues(OPTION_KEY);
        if (values == null || values.isEmpty()) {
            // `--mobruji.backfill-audio` 값 없이 들어오면 true 로 간주 (편의).
            return true;
        }
        return "true".equalsIgnoreCase(values.get(values.size() - 1));
    }

    record BackfillSummary(
            int analyzed,
            int successful,
            int updated,
            int skippedLowConfidence,
            int skippedImplausibleRange,
            int failed
    ) {
    }
}
