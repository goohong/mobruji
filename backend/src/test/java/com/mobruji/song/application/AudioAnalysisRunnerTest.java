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
import com.mobruji.song.domain.AudioAnalysisResult;

/**
 * {@link AudioAnalysisRunner} 단위 테스트. ProcessBuilder 호출 지점({@code startProcess})을 override 해
 * fake {@link Process} 를 주입하고 stdout JSON 파싱·exit code·timeout 분기를 검증한다.
 *
 * <p>실제 Python 호출은 통합 환경에서만 의미가 있어 이 클래스에서는 다루지 않는다.
 */
class AudioAnalysisRunnerTest {

    @TempDir
    private Path toolDir;

    @Test
    @DisplayName("analyzeByMetadata: stdout JSON 1줄을 도메인 record 로 파싱한다")
    void analyzeByMetadata_parsesStdoutJson() throws IOException {
        // given
        Files.createFile(toolDir.resolve("analyze.py"));
        final String stdout = """
                {"lowMidi":55,"highMidi":71,"key":"C","tempo":120.5,"durationSec":45.0,"confidence":0.78,"toolingVersion":"analyze-py-0.1.0"}
                """;
        final AudioAnalysisRunner runner = runnerWithFakeProcess(0, stdout, "");

        // when
        final AudioAnalysisResult result = runner.analyzeByMetadata("Yesterday", "The Beatles");

        // then
        assertThat(result.lowMidi()).isEqualTo(55);
        assertThat(result.highMidi()).isEqualTo(71);
        assertThat(result.key()).isEqualTo("C");
        assertThat(result.tempo()).isEqualTo(120.5);
        assertThat(result.durationSec()).isEqualTo(45.0);
        assertThat(result.confidence()).isEqualTo(0.78);
        assertThat(result.toolingVersion()).isEqualTo("analyze-py-0.1.0");
    }

    @Test
    @DisplayName("analyzeByYouTubeUrl: 마지막 비공백 라인이 JSON 이면 파싱한다 (Python warning 라인 무시)")
    void analyzeByYouTubeUrl_ignoresWarningLinesBeforeJson() throws IOException {
        // given
        Files.createFile(toolDir.resolve("analyze.py"));
        final String stdout = """
                WARNING: deprecated something
                INFO: analyzing https://youtube.com host=youtube.com
                {"lowMidi":48,"highMidi":72,"key":"G","tempo":98.0,"durationSec":30.0,"confidence":0.5,"toolingVersion":"analyze-py-0.1.0"}
                """;
        final AudioAnalysisRunner runner = runnerWithFakeProcess(0, stdout, "");

        // when
        final AudioAnalysisResult result = runner.analyzeByYouTubeUrl("https://www.youtube.com/watch?v=xxxx");

        // then
        assertThat(result.lowMidi()).isEqualTo(48);
        assertThat(result.highMidi()).isEqualTo(72);
        assertThat(result.tempo()).isEqualTo(98.0);
    }

    @Test
    @DisplayName("exit code != 0 이면 AudioAnalysisFailedException 으로 변환한다")
    void nonZeroExit_throwsFailedException() throws IOException {
        // given
        Files.createFile(toolDir.resolve("analyze.py"));
        final AudioAnalysisRunner runner = runnerWithFakeProcess(1, "", "yt-dlp: HTTP 403");

        // when / then
        assertThatThrownBy(() -> runner.analyzeByMetadata("t", "a"))
                .isInstanceOf(AudioAnalysisFailedException.class)
                .hasMessageContaining("exited with code 1");
    }

    @Test
    @DisplayName("stdout 에 error 필드만 있으면 AudioAnalysisFailedException")
    void errorPayload_throwsFailedException() throws IOException {
        // given
        Files.createFile(toolDir.resolve("analyze.py"));
        final String stdout = "{\"error\":\"yt-dlp blocked\",\"toolingVersion\":\"analyze-py-0.1.0\"}";
        final AudioAnalysisRunner runner = runnerWithFakeProcess(0, stdout, "");

        // when / then
        assertThatThrownBy(() -> runner.analyzeByMetadata("t", "a"))
                .isInstanceOf(AudioAnalysisFailedException.class)
                .hasMessageContaining("yt-dlp blocked");
    }

    @Test
    @DisplayName("필수 필드 누락 시 AudioAnalysisFailedException")
    void missingField_throwsFailedException() throws IOException {
        // given
        Files.createFile(toolDir.resolve("analyze.py"));
        final String stdout = "{\"lowMidi\":55,\"highMidi\":71,\"toolingVersion\":\"v\"}"; // durationSec/confidence 없음
        final AudioAnalysisRunner runner = runnerWithFakeProcess(0, stdout, "");

        // when / then
        assertThatThrownBy(() -> runner.analyzeByMetadata("t", "a"))
                .isInstanceOf(AudioAnalysisFailedException.class)
                .hasMessageContaining("missing required field");
    }

    @Test
    @DisplayName("tool-dir 가 존재하지 않으면 즉시 실패")
    void missingToolDir_throwsFailedException() {
        // given
        final AudioAnalysisProperties props = new AudioAnalysisProperties(
                "python3", "/no/such/dir/for/audio/analysis", Duration.ofSeconds(60),
                false, null, null);
        final AudioAnalysisRunner runner = new AudioAnalysisRunner(props);

        // when / then
        assertThatThrownBy(() -> runner.analyzeByMetadata("t", "a"))
                .isInstanceOf(AudioAnalysisFailedException.class)
                .hasMessageContaining("tool directory not found");
    }

    @Test
    @DisplayName("use-docker=true 시 docker compose run 명령으로 호출한다 (spec PR D)")
    void useDocker_buildsDockerComposeCommand() throws IOException {
        // given
        Files.createFile(toolDir.resolve("analyze.py"));
        final AudioAnalysisProperties props = new AudioAnalysisProperties(
                "python3", toolDir.toString(), Duration.ofSeconds(60),
                true, "/abs/path/docker-compose.audio.yml", "audio-analysis");
        final AudioAnalysisRunner runner = new AudioAnalysisRunner(props);

        // when
        final List<String> command = runner.buildCommand(List.of("--song-title", "Y", "--artist", "B"));

        // then: `docker compose -f <file> run --rm <service> <toolArgs>`
        assertThat(command).containsExactly(
                "docker", "compose", "-f", "/abs/path/docker-compose.audio.yml",
                "run", "--rm", "audio-analysis",
                "--song-title", "Y", "--artist", "B");
    }

    @Test
    @DisplayName("use-docker=false (기본) 는 호스트 python 명령으로 호출한다")
    void useDockerFalse_buildsHostPythonCommand() throws IOException {
        // given
        Files.createFile(toolDir.resolve("analyze.py"));
        final AudioAnalysisProperties props = new AudioAnalysisProperties(
                "python3", toolDir.toString(), Duration.ofSeconds(60),
                false, null, null);
        final AudioAnalysisRunner runner = new AudioAnalysisRunner(props);

        // when
        final List<String> command = runner.buildCommand(List.of("--song-title", "Y"));

        // then
        assertThat(command).containsExactly("python3", "analyze.py", "--song-title", "Y");
    }

    @Test
    @DisplayName("timeout 초과 시 destroyForcibly + AudioAnalysisFailedException")
    void timeout_throwsFailedException() throws IOException {
        // given
        Files.createFile(toolDir.resolve("analyze.py"));
        final AudioAnalysisProperties props = new AudioAnalysisProperties(
                "python3", toolDir.toString(), Duration.ofMillis(10),
                false, null, null);
        final HangingProcess hanging = new HangingProcess();
        final AudioAnalysisRunner runner = new AudioAnalysisRunner(props) {
            @Override
            protected Process startProcess(final List<String> command, final Path workingDir) {
                return hanging;
            }
        };

        // when / then
        assertThatThrownBy(() -> runner.analyzeByMetadata("t", "a"))
                .isInstanceOf(AudioAnalysisFailedException.class)
                .hasMessageContaining("timeout");
        assertThat(hanging.destroyed).isTrue();
    }

    @Test
    @DisplayName("빈 stdout 은 실패로 처리")
    void emptyStdout_throwsFailedException() throws IOException {
        // given
        Files.createFile(toolDir.resolve("analyze.py"));
        final AudioAnalysisRunner runner = runnerWithFakeProcess(0, "", "");

        // when / then
        assertThatThrownBy(() -> runner.analyzeByMetadata("t", "a"))
                .isInstanceOf(AudioAnalysisFailedException.class)
                .hasMessageContaining("empty stdout");
    }

    @Test
    @DisplayName("호스트 모드: analyze.py 가 toolDir 에 없으면 친화적 에러 메시지로 실패 (#207)")
    void missingAnalyzePy_throwsFriendlyMessage() {
        // given — toolDir 은 존재하지만 analyze.py 가 없는 상태
        final AudioAnalysisProperties props = new AudioAnalysisProperties(
                "python3", toolDir.toString(), Duration.ofSeconds(60),
                false, null, null);
        final AudioAnalysisRunner runner = new AudioAnalysisRunner(props);

        // when / then
        assertThatThrownBy(() -> runner.analyzeByMetadata("t", "a"))
                .isInstanceOf(AudioAnalysisFailedException.class)
                .hasMessageContaining("analyze.py not found")
                .hasMessageContaining("tools/audio-analysis");
    }

    @Test
    @DisplayName("호스트 모드: Python 실행 파일 ENOENT 시 venv 셋업 안내 메시지 (#207)")
    void spawnFailure_includesVenvHelp() throws IOException {
        // given
        Files.createFile(toolDir.resolve("analyze.py"));
        final AudioAnalysisProperties props = new AudioAnalysisProperties(
                "/no/such/python/binary", toolDir.toString(), Duration.ofSeconds(60),
                false, null, null);
        final AudioAnalysisRunner runner = new AudioAnalysisRunner(props) {
            @Override
            protected Process startProcess(final List<String> command, final Path workingDir) throws IOException {
                throw new IOException("Cannot run program \"/no/such/python/binary\": "
                        + "error=2, No such file or directory");
            }
        };

        // when / then
        assertThatThrownBy(() -> runner.analyzeByMetadata("t", "a"))
                .isInstanceOf(AudioAnalysisFailedException.class)
                .hasMessageContaining("Python 실행 파일을 찾을 수 없습니다")
                .hasMessageContaining("python3 -m venv .venv")
                .hasMessageContaining("AUDIO_ANALYSIS_PYTHON_CMD");
    }

    @Test
    @DisplayName("docker 모드: docker CLI ENOENT 시 docker 설치/호스트 모드 안내 (#207)")
    void spawnFailure_dockerMode_includesDockerHelp() throws IOException {
        // given
        Files.createFile(toolDir.resolve("analyze.py"));
        final AudioAnalysisProperties props = new AudioAnalysisProperties(
                "python3", toolDir.toString(), Duration.ofSeconds(60),
                true, "/abs/docker-compose.audio.yml", "audio-analysis");
        final AudioAnalysisRunner runner = new AudioAnalysisRunner(props) {
            @Override
            protected Process startProcess(final List<String> command, final Path workingDir) throws IOException {
                throw new IOException("Cannot run program \"docker\": error=2, No such file or directory");
            }
        };

        // when / then
        assertThatThrownBy(() -> runner.analyzeByMetadata("t", "a"))
                .isInstanceOf(AudioAnalysisFailedException.class)
                .hasMessageContaining("docker CLI not found")
                .hasMessageContaining("use-docker=false");
    }

    @Test
    @DisplayName("호스트 모드: Python 실행 파일 권한 없음 시 chmod 안내 메시지 (#207)")
    void spawnFailure_permissionDenied_includesChmodHelp() throws IOException {
        // given
        Files.createFile(toolDir.resolve("analyze.py"));
        final AudioAnalysisProperties props = new AudioAnalysisProperties(
                "/some/python", toolDir.toString(), Duration.ofSeconds(60),
                false, null, null);
        final AudioAnalysisRunner runner = new AudioAnalysisRunner(props) {
            @Override
            protected Process startProcess(final List<String> command, final Path workingDir) throws IOException {
                throw new IOException("Cannot run program \"/some/python\": error=13, Permission denied");
            }
        };

        // when / then
        assertThatThrownBy(() -> runner.analyzeByMetadata("t", "a"))
                .isInstanceOf(AudioAnalysisFailedException.class)
                .hasMessageContaining("실행 권한이 없습니다")
                .hasMessageContaining("chmod +x");
    }

    // ---------- helpers ----------

    private AudioAnalysisRunner runnerWithFakeProcess(
            final int exitCode, final String stdout, final String stderr) {
        final AudioAnalysisProperties props = new AudioAnalysisProperties(
                "python3", toolDir.toString(), Duration.ofSeconds(60),
                false, null, null);
        return new AudioAnalysisRunner(props) {
            @Override
            protected Process startProcess(final List<String> command, final Path workingDir) {
                return new FakeProcess(exitCode, stdout, stderr);
            }
        };
    }

    /** 즉시 종료하는 fake Process. stdout/stderr/exit code 만 제어한다. */
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

    /** 절대 종료되지 않는 fake Process — timeout 분기 검증용. */
    private static final class HangingProcess extends Process {
        boolean destroyed = false;

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(final long timeout, final TimeUnit unit) {
            return false;
        }

        @Override
        public int exitValue() {
            throw new IllegalThreadStateException();
        }

        @Override
        public void destroy() {
            destroyed = true;
        }

        @Override
        public Process destroyForcibly() {
            destroyed = true;
            return this;
        }

        @Override
        public boolean isAlive() {
            return !destroyed;
        }
    }
}
