package com.mobruji.song.application;

import java.time.Duration;
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
 * <p>음역대 미보유 곡 backfill(이슈 #1739) — {@code --mobruji.backfill-audio.target=missing-range} 추가 시
 * 신뢰도/출처 기반 넓은 후보 대신 {@code lowMidi/highMidi} 가 비어 추천 풀에서 빠진 곡만 정밀 타겟한다.
 * {@code --mobruji.backfill-audio.limit=N} 으로 chunk, {@code --mobruji.backfill-audio.sleep-seconds=N} 으로
 * 곡간 대기(YouTube rate limit)를 둬 271곡을 안전 단위로 반복 backfill 한다 (성공 곡은 다음 실행 후보에서 자동 제외):
 * <pre>
 * ./gradlew bootRun --args='--spring.profiles.active=local --mobruji.backfill-audio=true
 * --mobruji.backfill-audio.target=missing-range --mobruji.backfill-audio.limit=20
 * --mobruji.backfill-audio.sleep-seconds=3'
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
     * backfill 대상 선택 옵션 키 — {@code --mobruji.backfill-audio.target=missing-range}. 지정 시 신뢰도/출처 기반
     * 넓은 후보({@link SongRepository#findCandidatesForBackfill(double)}) 대신 음역대 미보유 곡
     * ({@link SongRepository#findMissingVocalRange()}) 만 정밀 타겟한다. 이슈 #1739 — 추천 풀에서 빠진 임포트 곡을
     * YouTube ytsearch 자동매칭 + 자체분석으로 채워 추천 진입시킨다. {@link #OPTION_LIMIT_KEY} 와 합성해 chunk 처리.
     */
    static final String OPTION_TARGET_KEY = "mobruji.backfill-audio.target";

    /** {@link #OPTION_TARGET_KEY} 의 음역대 미보유 곡 타겟 값. */
    static final String TARGET_MISSING_RANGE = "missing-range";

    /**
     * 곡간 대기(rate limit) 옵션 키 — {@code --mobruji.backfill-audio.sleep-seconds=N}. 곡마다 YouTube
     * ytsearch/다운로드가 발생하므로 대량 backfill 시 곡 사이에 N초 대기해 rate limit/디스크 부하를 분산한다
     * (이슈 #1739, batch_analyze.py 의 {@code --sleep-seconds} 와 정합). 미지정/0 이면 대기 없음.
     */
    static final String OPTION_SLEEP_SECONDS_KEY = "mobruji.backfill-audio.sleep-seconds";

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
        final Duration delay = parseSleepSeconds(args);
        if (isMissingRangeTarget(args)) {
            runMissingVocalRangeBackfill(limit, delay, DEFAULT_CONFIDENCE_THRESHOLD);
        } else if (limit.isPresent()) {
            runBoundedCandidateBackfill(limit.getAsInt(), delay, DEFAULT_CONFIDENCE_THRESHOLD);
        } else {
            runBackfill(songRepository.findAll(), DEFAULT_CONFIDENCE_THRESHOLD, delay);
        }
    }

    /**
     * 음역대 미보유 곡({@link SongRepository#findMissingVocalRange()}) 만 골라 backfill 한다 (이슈 #1739). 추천 풀에서
     * 빠진 임포트 곡을 YouTube ytsearch 자동매칭 + 자체분석으로 채워 추천 진입시키는 진입점. {@code limit} 이 있으면
     * id 순 앞에서 그만큼만 처리해 chunk 단위로 안전하게 반복 실행한다 — 한 곡이 성공하면 다음 실행의 후보에서 빠지므로
     * (lowMidi/highMidi 가 채워져) 자연 resume 된다.
     *
     * @param limit     처리할 곡 상한 (미지정 시 전체 미보유 곡)
     * @param delay     곡간 대기 (rate limit)
     * @param threshold 적용 임계 confidence
     * @return 처리 요약
     */
    BackfillSummary runMissingVocalRangeBackfill(
            final OptionalInt limit, final Duration delay, final double threshold) {
        final List<Song> missing = songRepository.findMissingVocalRange();
        final List<Song> selected = limit.isPresent()
                ? missing.stream().limit(limit.getAsInt()).toList()
                : missing;
        LOG.info(
                "audio backfill missing-range batch: missing={} limit={} selected={} delaySeconds={}",
                missing.size(), limit.isPresent() ? limit.getAsInt() : -1,
                selected.size(), delay.toSeconds());
        return runBackfill(selected, threshold, delay);
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
        return runBoundedCandidateBackfill(limit, Duration.ZERO, confidenceThreshold);
    }

    /**
     * {@link #runBoundedCandidateBackfill(int, double)} 와 동일하되 곡간 대기(rate limit)를 명시한다.
     */
    BackfillSummary runBoundedCandidateBackfill(
            final int limit, final Duration delay, final double confidenceThreshold) {
        final List<Song> candidates = songRepository.findCandidatesForBackfill(confidenceThreshold);
        final List<Song> selected = candidates.stream().limit(limit).toList();
        LOG.info(
                "audio backfill bounded validation batch: candidates={} limit={} selected={} delaySeconds={}",
                candidates.size(), limit, selected.size(), delay.toSeconds());
        return runBackfill(selected, confidenceThreshold, delay);
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
     * 곡을 selective 하게 결정해 호출할 수 있도록 노출한다. 곡간 대기 없이 처리한다.
     */
    BackfillSummary runBackfill(final List<Song> songs, final double confidenceThreshold) {
        return runBackfill(songs, confidenceThreshold, Duration.ZERO);
    }

    /**
     * 임의 곡 집합에 대해 backfill 실행하되 곡 사이에 {@code delay} 만큼 대기한다. 곡마다 YouTube ytsearch/다운로드가
     * 발생하므로 대량 backfill 시 곡간 대기로 rate limit/디스크 부하를 분산한다 (이슈 #1739). 대기는 마지막 곡 뒤에는
     * 두지 않으며, {@code delay} 가 0/음수면 대기 없이 처리한다.
     */
    BackfillSummary runBackfill(
            final List<Song> songs, final double confidenceThreshold, final Duration delay) {
        backfillRequestedCounter.increment();
        int analyzed = 0;
        int successful = 0;
        int updated = 0;
        int skippedLowConfidence = 0;
        int failed = 0;
        for (int index = 0; index < songs.size(); index++) {
            final Song song = songs.get(index);
            Objects.requireNonNull(song, "song must not be null");
            if (index > 0) {
                sleepBetweenSongs(delay);
            }
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
                "audio backfill done analyzed={} successful={} updated={} skipped_low_confidence={} failed={}",
                analyzed, successful, updated, skippedLowConfidence, failed);
        LAST_BACKFILL_COMPLETED_AT.set(Instant.now());
        return new BackfillSummary(analyzed, successful, updated, skippedLowConfidence, failed);
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

    /**
     * {@code --mobruji.backfill-audio.target=missing-range} 여부. 그 외 값/미지정은 false (기존 후보 경로 유지).
     */
    private static boolean isMissingRangeTarget(final ApplicationArguments args) {
        if (args == null || !args.containsOption(OPTION_TARGET_KEY)) {
            return false;
        }
        final List<String> values = args.getOptionValues(OPTION_TARGET_KEY);
        if (values == null || values.isEmpty()) {
            return false;
        }
        return TARGET_MISSING_RANGE.equalsIgnoreCase(values.get(values.size() - 1).trim());
    }

    /**
     * {@code --mobruji.backfill-audio.sleep-seconds=N} 파싱 — 미지정/빈 값이면 {@link Duration#ZERO}.
     * 0 이상 정수만 허용하며 음수/비정수는 잘못된 수동 trigger 이므로 fail-fast 한다.
     */
    private static Duration parseSleepSeconds(final ApplicationArguments args) {
        if (args == null || !args.containsOption(OPTION_SLEEP_SECONDS_KEY)) {
            return Duration.ZERO;
        }
        final List<String> values = args.getOptionValues(OPTION_SLEEP_SECONDS_KEY);
        if (values == null || values.isEmpty()) {
            return Duration.ZERO;
        }
        final String raw = values.get(values.size() - 1).trim();
        final int parsed;
        try {
            parsed = Integer.parseInt(raw);
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException(
                    "--" + OPTION_SLEEP_SECONDS_KEY + " 는 0 이상 정수여야 합니다: " + raw, e);
        }
        if (parsed < 0) {
            throw new IllegalArgumentException(
                    "--" + OPTION_SLEEP_SECONDS_KEY + " 는 0 이상 정수여야 합니다: " + parsed);
        }
        return Duration.ofSeconds(parsed);
    }

    /**
     * 곡간 rate limit 대기. 테스트가 실제 sleep 없이 호출 횟수만 검증할 수 있도록 seam 으로 분리한다.
     * {@code delay} 가 null/0/음수면 즉시 반환한다.
     */
    protected void sleepBetweenSongs(final Duration delay) {
        if (delay == null || delay.isZero() || delay.isNegative()) {
            return;
        }
        try {
            Thread.sleep(delay.toMillis());
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
            int failed
    ) {
    }
}
