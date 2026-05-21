package com.mobruji.song.application;

import java.util.List;
import java.util.Objects;

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

/**
 * 시드 30곡(또는 DB 전체)의 lowMidi/highMidi 를 Python audio analysis tool 산출값으로 backfill 하는 일회성 batch.
 *
 * <p>spec: {@code docs/features/audio-tooling-bootstrap.md} PR C — 수기 시드의 음역대 정확도를 audio 분석으로
 * 끌어올린다. 추천 알고리즘 코드는 그대로이고 입력 데이터만 정확해지므로 결정성 회귀는 없다.
 *
 * <p>실행 방법(수동 trigger, 운영 안전):
 * <pre>
 * ./gradlew bootRun --args='--spring.profiles.active=local --mobruji.backfill-audio=true'
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
     * 적용 임계 confidence — 작업 지시 기본 0.6. 임계 미달은 수기 값을 보존한다.
     */
    static final double DEFAULT_CONFIDENCE_THRESHOLD = 0.6;

    private static final Logger LOG = LoggerFactory.getLogger(SongAudioBackfillCommand.class);

    private final SongRepository songRepository;
    private final AudioAnalysisRunner audioAnalysisRunner;

    public SongAudioBackfillCommand(
            final SongRepository songRepository,
            final AudioAnalysisRunner audioAnalysisRunner) {
        this.songRepository = songRepository;
        this.audioAnalysisRunner = audioAnalysisRunner;
    }

    @Override
    public void run(final ApplicationArguments args) {
        if (!isOptionTrue(args)) {
            return;
        }
        runBackfill(DEFAULT_CONFIDENCE_THRESHOLD);
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
        int analyzed = 0;
        int successful = 0;
        int updated = 0;
        int skippedLowConfidence = 0;
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
                failed++;
                continue;
            } catch (final RuntimeException e) {
                // ProcessBuilder/IO 계열 unchecked 예외도 곡 단위로 격리 — 전체 batch 중단 회피.
                LOG.warn(
                        "audio backfill error songId={} title={} reason={}",
                        song.getId(), song.getTitle(), e.getMessage());
                failed++;
                continue;
            }
            successful++;
            final boolean changed;
            try {
                changed = song.backfillFromAudioAnalysis(result, confidenceThreshold);
            } catch (final RuntimeException e) {
                // backfill 자체에서 도메인 검증 실패 (예: lowMidi>highMidi) — 곡 단위 격리.
                LOG.warn(
                        "audio backfill apply failed songId={} title={} reason={}",
                        song.getId(), song.getTitle(), e.getMessage());
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
        return new BackfillSummary(analyzed, successful, updated, skippedLowConfidence, failed);
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
