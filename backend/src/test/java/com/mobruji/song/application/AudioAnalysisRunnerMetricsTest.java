package com.mobruji.song.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.mobruji.song.domain.AudioAnalysisFailedException;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * {@link AudioAnalysisRunner} Micrometer timer emit 검증.
 *
 * <p>spec: {@code docs/features/observability-baseline.md} §5-3 — `mobruji.song.audio.analysis.duration`
 * timer 가 성공/실패 양쪽 호출 모두에 대해 wall-clock 을 기록하는지 가드. 실패도 측정에 포함되어 분포(p95) 가
 * 왜곡 없이 보이도록 한다.
 */
class AudioAnalysisRunnerMetricsTest {

    @TempDir
    private Path toolDir;

    @Test
    @DisplayName("analyzeByMetadata 성공 1회 = mobruji.song.audio.analysis.duration timer count +1")
    void analyzeByMetadata_recordsDurationOnSuccess() throws IOException {
        // given
        Files.createFile(toolDir.resolve("analyze.py"));
        final MeterRegistry registry = new SimpleMeterRegistry();
        final String stdout = "{\"lowMidi\":55,\"highMidi\":71,\"key\":\"C\",\"tempo\":120.0,"
                + "\"durationSec\":45.0,\"confidence\":0.78,\"toolingVersion\":\"v\"}";
        final AudioAnalysisRunner runner = runnerWithFakeProcess(registry, 0, stdout, "");

        // when
        runner.analyzeByMetadata("Yesterday", "The Beatles");

        // then
        final Timer timer = registry.find(AudioAnalysisRunner.METRIC_ANALYSIS_DURATION).timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1L);
        assertThat(timer.totalTime(TimeUnit.NANOSECONDS)).isPositive();
    }

    @Test
    @DisplayName("analyzeByMetadata 실패도 timer count +1 (분포 왜곡 방지)")
    void analyzeByMetadata_recordsDurationOnFailure() throws IOException {
        // given — exit code 1 = AudioAnalysisFailedException
        Files.createFile(toolDir.resolve("analyze.py"));
        final MeterRegistry registry = new SimpleMeterRegistry();
        final AudioAnalysisRunner runner = runnerWithFakeProcess(registry, 1, "", "yt-dlp: HTTP 403");

        // when / then
        assertThatThrownBy(() -> runner.analyzeByMetadata("t", "a"))
                .isInstanceOf(AudioAnalysisFailedException.class);

        final Timer timer = registry.find(AudioAnalysisRunner.METRIC_ANALYSIS_DURATION).timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("analyzeByYouTubeUrl 호출도 동일 timer 에 기록")
    void analyzeByYouTubeUrl_recordsToSameDurationTimer() throws IOException {
        // given
        Files.createFile(toolDir.resolve("analyze.py"));
        final MeterRegistry registry = new SimpleMeterRegistry();
        final String stdout = "{\"lowMidi\":48,\"highMidi\":72,\"key\":\"G\",\"tempo\":98.0,"
                + "\"durationSec\":30.0,\"confidence\":0.5,\"toolingVersion\":\"v\"}";
        final AudioAnalysisRunner runner = runnerWithFakeProcess(registry, 0, stdout, "");

        // when
        runner.analyzeByMetadata("Yesterday", "The Beatles");
        runner.analyzeByYouTubeUrl("https://www.youtube.com/watch?v=xxxx");

        // then: 2회 호출 모두 같은 timer 에 누적
        final Timer timer = registry.find(AudioAnalysisRunner.METRIC_ANALYSIS_DURATION).timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(2L);
    }

    // ---------- helpers ----------

    private AudioAnalysisRunner runnerWithFakeProcess(
            final MeterRegistry registry, final int exitCode, final String stdout, final String stderr) {
        final AudioAnalysisProperties props = new AudioAnalysisProperties(
                "python3", toolDir.toString(), Duration.ofSeconds(60),
                false, null, null);
        return new AudioAnalysisRunner(props, registry) {
            @Override
            protected Process startProcess(final List<String> command, final Path workingDir) {
                return new FakeProcess(exitCode, stdout, stderr);
            }
        };
    }

    /** 즉시 종료하는 fake Process — {@link AudioAnalysisRunnerTest} 의 동일 fake 와 의도적으로 동일 구조. */
    private static final class FakeProcess extends Process {
        private final int exitCode;
        private final InputStream stdout;
        private final InputStream stderr;

        FakeProcess(final int exitCode, final String stdout, final String stderr) {
            this.exitCode = exitCode;
            this.stdout = new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
            this.stderr = new ByteArrayInputStream(stderr.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return stderr;
        }

        @Override
        public int waitFor() {
            return exitCode;
        }

        @Override
        public boolean waitFor(final long timeout, final TimeUnit unit) {
            return true;
        }

        @Override
        public int exitValue() {
            return exitCode;
        }

        @Override
        public void destroy() {
            /* no-op */ }

        @Override
        public Process destroyForcibly() {
            return this;
        }

        @Override
        public boolean isAlive() {
            return false;
        }
    }
}
