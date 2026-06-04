package com.mobruji.song.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.domain.VocalRangeEstimate;
import com.mobruji.song.domain.VocalRangeEstimator;
import com.mobruji.song.infrastructure.SongRepository;

/**
 * 음역대 미보유 곡({@link SongRepository#findMissingVocalRange()})에 keyOriginal/genre 메타만으로 음역대를 추정해
 * 채우는 일회성 batch — 오디오 자체분석 인프라 부재(#1778) 동안의 interim 경로. 자체분석({@link SongAudioBackfillCommand})
 * 이 YouTube 다운로드 + Python 분석으로 정밀 음역대를 채우는 것과 달리, 본 batch 는 오디오 없이 즉시 산출한다.
 *
 * <p>추천 후보 universe({@link SongRepository#findAllWithVocalRange()})는 음역대 보유 곡만 추리므로, 음역대 미보유
 * 임포트 곡은 추천 풀에서 빠져 있었다. 메타 추정으로 음역대를 채워 곧장 추천 진입시켜 추천 다양성을 끌어올린다
 * ({@code metadataSource=ESTIMATED}, {@code metadataConfidence} 낮음 → 추후 자체분석이 임계 통과 시 덮어쓴다).
 *
 * <p>곡 단위 자체 트랜잭션 ({@link SongAudioBackfillCommand} 와 동일 격리 정책) — 한 곡 적용 실패가 다음 곡을 막지 않는다.
 *
 * <p>실행 방법(수동 trigger, 운영 안전):
 * <pre>
 * ./gradlew bootRun --args='--spring.profiles.active=local --mobruji.estimate-range=true'
 * </pre>
 *
 * <p>소량 검증 배치 — {@code --mobruji.estimate-range.limit=N} 추가 시 미보유 곡 중 id 순 앞 N곡만 처리한다:
 * <pre>
 * ./gradlew bootRun --args='--spring.profiles.active=local --mobruji.estimate-range=true
 * --mobruji.estimate-range.limit=20'
 * </pre>
 *
 * <p>인자({@link ApplicationArguments}) 미지정 시 no-op — 평시 부팅(테스트 포함)에 영향 없음.
 */
@Component
public class SongVocalRangeEstimateCommand implements ApplicationRunner {

    /** ApplicationArguments 에서 인식할 옵션 키. {@code --mobruji.estimate-range=true} */
    static final String OPTION_KEY = "mobruji.estimate-range";

    /**
     * 소량 검증 배치 옵션 키 — {@code --mobruji.estimate-range.limit=N}. 지정 시 미보유 곡 중 id 순 앞 N곡만 처리한다.
     * 양의 정수만 허용하며 0/음수/비정수는 잘못된 수동 trigger 이므로 fail-fast 한다. 미지정 시 전체 미보유 곡 처리.
     */
    static final String OPTION_LIMIT_KEY = "mobruji.estimate-range.limit";

    private static final Logger LOG = LoggerFactory.getLogger(SongVocalRangeEstimateCommand.class);

    private final SongRepository songRepository;

    public SongVocalRangeEstimateCommand(final SongRepository songRepository) {
        this.songRepository = songRepository;
    }

    @Override
    public void run(final ApplicationArguments args) {
        if (!isOptionTrue(args)) {
            return;
        }
        runEstimate(parseLimit(args));
    }

    /**
     * 음역대 미보유 곡에 메타 추정 음역대를 채운다. {@code limit} 이 있으면 id 순 앞에서 그만큼만 처리해 chunk 단위로
     * 안전하게 반복 실행한다 — 한 곡이 적용되면 다음 실행의 후보에서 빠지므로(lowMidi/highMidi 가 채워져) 자연 resume 된다.
     *
     * @param limit 처리할 곡 상한 (미지정 시 전체 미보유 곡)
     * @return 처리 요약
     */
    EstimateSummary runEstimate(final OptionalInt limit) {
        final List<Song> missing = songRepository.findMissingVocalRange();
        final List<Song> selected = limit.isPresent()
                ? missing.stream().limit(limit.getAsInt()).toList()
                : missing;
        int scanned = 0;
        int applied = 0;
        int skippedUnestimable = 0;
        int skippedNotApplied = 0;
        for (final Song song : selected) {
            Objects.requireNonNull(song, "song must not be null");
            scanned++;
            final MusicalKey keyOriginal = song.getKeyOriginal();
            final Optional<VocalRangeEstimate> estimate = VocalRangeEstimator.estimate(keyOriginal, song.getGenre());
            if (estimate.isEmpty()) {
                skippedUnestimable++;
                continue;
            }
            if (song.applyEstimatedVocalRange(estimate.get())) {
                songRepository.save(song);
                applied++;
            } else {
                skippedNotApplied++;
            }
        }
        LOG.info(
                "vocal range estimate done: missing={} scanned={} applied={} "
                        + "skipped_unestimable={} skipped_not_applied={}",
                missing.size(), scanned, applied, skippedUnestimable, skippedNotApplied);
        return new EstimateSummary(scanned, applied, skippedUnestimable, skippedNotApplied);
    }

    /**
     * {@code --mobruji.estimate-range.limit=N} 파싱 — 미지정/빈 값이면 {@link OptionalInt#empty()}.
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
            return true;
        }
        return "true".equalsIgnoreCase(values.get(values.size() - 1));
    }

    record EstimateSummary(
            int scanned,
            int applied,
            int skippedUnestimable,
            int skippedNotApplied
    ) {
    }
}
