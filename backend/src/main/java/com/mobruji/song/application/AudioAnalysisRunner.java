package com.mobruji.song.application;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobruji.song.domain.AudioAnalysisFailedException;
import com.mobruji.song.domain.AudioAnalysisResult;

/**
 * Python audio analysis tool ({@code tools/audio-analysis/analyze.py}) 호출 어댑터.
 *
 * <p>spec: {@code docs/features/audio-tooling-bootstrap.md} §3 — Spring Boot 는 ProcessBuilder 로
 * Python CLI 를 호출하고 stdout JSON 을 파싱한다. 본 PR(C) 범위는 단일 곡 동기 호출이며 fallback
 * (재시도/큐잉/`analysis_status=FAILED` 마킹) 은 PR D 의 호출 측에서 본 예외를 catch 해 수행한다.
 *
 * <p>ADR 0005 §A-7: 외부 process 호출은 엄밀히 infrastructure 어댑터 후보이나 v0.x 단축형에서는 application
 * 직접 호출을 허용한다. 향후 별도 spec 에서 infrastructure 로 이동 가능.
 *
 * <p>로깅 정책: spec §3 비기능 — `songId`/`step`/`elapsedMs`/`exitCode` 만 INFO. URL 원문/PII 금지.
 * 본 어댑터는 곡 title/artist/url 인자만 받으며 url 은 host 만 로깅한다 (Python 측 mask_url 와 정합).
 */
@Component
public class AudioAnalysisRunner {

    private static final Logger LOG = LoggerFactory.getLogger(AudioAnalysisRunner.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int STDERR_SNIPPET_LIMIT = 512;

    private final AudioAnalysisProperties properties;

    public AudioAnalysisRunner(final AudioAnalysisProperties properties) {
        this.properties = properties;
    }

    /**
     * 제목 + 아티스트로 ytsearch 기반 분석을 수행한다. spec PR D 의 시드 적재 시나리오 (메타만 있는 신곡)에서 사용.
     */
    public AudioAnalysisResult analyzeByMetadata(final String title, final String artist) {
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(artist, "artist must not be null");
        final List<String> args = new ArrayList<>();
        args.add("--song-title");
        args.add(title);
        args.add("--artist");
        args.add(artist);
        return execute(args, "meta:" + title + " / " + artist);
    }

    /**
     * YouTube URL 을 직접 지정해 분석한다. 큐레이터 콘솔에서 URL 검증을 마친 입력에 사용.
     */
    public AudioAnalysisResult analyzeByYouTubeUrl(final String youtubeUrl) {
        Objects.requireNonNull(youtubeUrl, "youtubeUrl must not be null");
        final List<String> args = new ArrayList<>();
        args.add("--youtube-url");
        args.add(youtubeUrl);
        return execute(args, "url:" + maskUrl(youtubeUrl));
    }

    private AudioAnalysisResult execute(final List<String> toolArgs, final String logTag) {
        final Path toolDir = Path.of(properties.toolDir());
        if (!Files.isDirectory(toolDir)) {
            throw new AudioAnalysisFailedException(
                    "audio analysis tool directory not found: " + properties.toolDir()
                            + " — README 의 '설치' 섹션을 참고해 tools/audio-analysis 를 셋업하거나"
                            + " audio.analysis.tool-dir (또는 AUDIO_ANALYSIS_TOOL_DIR) 를 올바른 경로로 지정하세요.");
        }
        if (!properties.useDocker() && !Files.isRegularFile(toolDir.resolve("analyze.py"))) {
            throw new AudioAnalysisFailedException(
                    "analyze.py not found under tool directory: " + properties.toolDir()
                            + " — git repo 의 tools/audio-analysis 하위에 analyze.py 가 있어야 합니다.");
        }
        final List<String> command = buildCommand(toolArgs);

        final long startedAt = System.currentTimeMillis();
        final Process process;
        try {
            process = startProcess(command, toolDir);
        } catch (final IOException e) {
            throw new AudioAnalysisFailedException(buildSpawnFailureMessage(command, e), e);
        }

        final String stdout;
        final String stderr;
        final int exitCode;
        try {
            final boolean finished = process.waitFor(
                    properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new AudioAnalysisFailedException(
                        "audio analysis timeout after " + properties.timeout().toSeconds()
                                + "s (tag=" + logTag + ")");
            }
            exitCode = process.exitValue();
            stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new AudioAnalysisFailedException("audio analysis interrupted (tag=" + logTag + ")", e);
        } catch (final IOException e) {
            throw new UncheckedIOException("failed to drain audio analysis stdio", e);
        }

        final long elapsedMs = System.currentTimeMillis() - startedAt;
        if (exitCode != 0) {
            LOG.warn("audio analysis failed tag={} exitCode={} elapsedMs={} stderr={}",
                    logTag, exitCode, elapsedMs, truncate(stderr));
            throw new AudioAnalysisFailedException(
                    "audio analysis exited with code " + exitCode + " (tag=" + logTag + ")");
        }
        LOG.info("audio analysis ok tag={} elapsedMs={}", logTag, elapsedMs);
        return parse(stdout);
    }

    /**
     * ProcessBuilder 호출 지점. 단위 테스트에서 fake process 를 주입할 수 있도록 protected 로 분리한다.
     */
    protected Process startProcess(final List<String> command, final Path workingDir) throws IOException {
        return new ProcessBuilder(command)
                .directory(workingDir.toFile())
                .redirectErrorStream(false)
                .start();
    }

    /**
     * 호스트 Python vs docker compose 분기. spec PR D — {@code audio.analysis.use-docker=true} 시
     * Python 의존성을 호스트에 설치하지 않고 컨테이너에서 실행한다.
     *
     * <p>docker 모드 명령:
     * {@code docker compose -f <compose-file> run --rm <service> --song-title ...}.
     * Dockerfile 의 ENTRYPOINT 가 {@code ["python", "analyze.py"]} 이므로 toolArgs 만 그대로 전달한다.
     *
     * <p>호스트 모드 명령: {@code <python-cmd> analyze.py <toolArgs>}.
     */
    List<String> buildCommand(final List<String> toolArgs) {
        final List<String> command = new ArrayList<>();
        if (properties.useDocker()) {
            command.add("docker");
            command.add("compose");
            final String composeFile = properties.dockerComposeFile();
            if (composeFile != null && !composeFile.isBlank()) {
                command.add("-f");
                command.add(composeFile);
            }
            command.add("run");
            command.add("--rm");
            command.add(properties.dockerComposeService());
            command.addAll(toolArgs);
            return command;
        }
        command.add(properties.pythonCmd());
        command.add("analyze.py");
        command.addAll(toolArgs);
        return command;
    }

    private AudioAnalysisResult parse(final String stdout) {
        if (stdout == null || stdout.isBlank()) {
            throw new AudioAnalysisFailedException("audio analysis returned empty stdout");
        }
        final String jsonLine = lastNonBlankLine(stdout);
        try {
            final JsonNode root = OBJECT_MAPPER.readTree(jsonLine);
            if (root.hasNonNull("error")) {
                throw new AudioAnalysisFailedException(
                        "audio analysis tool reported error: " + root.get("error").asText());
            }
            final int lowMidi = requireInt(root, "lowMidi");
            final int highMidi = requireInt(root, "highMidi");
            final String key = root.hasNonNull("key") ? root.get("key").asText() : null;
            final Double tempo = root.hasNonNull("tempo") ? root.get("tempo").asDouble() : null;
            final double durationSec = requireDouble(root, "durationSec");
            final double confidence = requireDouble(root, "confidence");
            final String toolingVersion = root.hasNonNull("toolingVersion")
                    ? root.get("toolingVersion").asText()
                    : "unknown";
            return new AudioAnalysisResult(
                    lowMidi, highMidi, key, tempo, durationSec, confidence, toolingVersion);
        } catch (final AudioAnalysisFailedException e) {
            throw e;
        } catch (final RuntimeException | IOException e) {
            throw new AudioAnalysisFailedException("failed to parse audio analysis JSON", e);
        }
    }

    private static int requireInt(final JsonNode root, final String field) {
        if (!root.hasNonNull(field)) {
            throw new AudioAnalysisFailedException("missing required field: " + field);
        }
        return root.get(field).asInt();
    }

    private static double requireDouble(final JsonNode root, final String field) {
        if (!root.hasNonNull(field)) {
            throw new AudioAnalysisFailedException("missing required field: " + field);
        }
        return root.get(field).asDouble();
    }

    private static String lastNonBlankLine(final String stdout) {
        final String[] lines = stdout.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) {
                return lines[i].trim();
            }
        }
        return stdout.trim();
    }

    private static String truncate(final String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= STDERR_SNIPPET_LIMIT) {
            return text;
        }
        return text.substring(0, STDERR_SNIPPET_LIMIT) + "...(truncated)";
    }

    /**
     * spawn 실패 시 사용자(보통 로컬 개발자/배포 운영자)가 다음 액션을 알 수 있도록 친화 메시지를 만든다.
     *
     * <p>전형적 케이스:
     * <ul>
     * <li>호스트 모드, pythonCmd 가 ENOENT — Python venv 미설치 또는 경로 오류 → venv 셋업 안내</li>
     * <li>호스트 모드, AccessDenied — 실행 권한 없음 → chmod 안내</li>
     * <li>docker 모드, ENOENT — docker CLI 미설치 → docker 설치 안내</li>
     * </ul>
     *
     * <p>부트 fail-fast 가 아닌 first-invocation 시점 안내라는 점에 유의 (spec PR D 정책: 부팅은
     * 무조건 성공, 호출 시점에 실패).
     */
    private String buildSpawnFailureMessage(final List<String> command, final IOException cause) {
        final String head = command.get(0);
        final String causeMessage = cause.getMessage() == null ? "" : cause.getMessage();
        final boolean notFound = cause instanceof NoSuchFileException
                || causeMessage.toLowerCase(Locale.ROOT).contains("no such file")
                || causeMessage.toLowerCase(Locale.ROOT).contains("cannot run program");
        final boolean denied = cause instanceof AccessDeniedException
                || causeMessage.toLowerCase(Locale.ROOT).contains("permission denied");
        // denied 우선 — JDK 의 "Cannot run program ..." prefix 는 두 케이스 모두에 붙어
        // notFound 패턴과 겹친다. errno (error=13 / Permission denied) 기반으로 먼저 분기.
        if (properties.useDocker()) {
            if (denied) {
                return "failed to spawn audio analysis process: docker CLI 실행 권한이 없습니다 ('"
                        + head + "'). 사용자 계정을 docker 그룹에 추가하거나 sudo 권한을 확인하세요.";
            }
            if (notFound) {
                return "failed to spawn audio analysis process: docker CLI not found ('"
                        + head + "'). docker desktop 또는 docker engine 을 설치하거나"
                        + " audio.analysis.use-docker=false 로 호스트 Python 모드를 사용하세요.";
            }
            return "failed to spawn audio analysis process: " + head
                    + " (docker mode) — 원인: " + causeMessage;
        }
        if (denied) {
            return "failed to spawn audio analysis process: Python 실행 파일에 실행 권한이 없습니다 ('"
                    + head + "'). `chmod +x " + head + "` 로 권한을 부여하거나"
                    + " venv 를 재생성하세요.";
        }
        if (notFound) {
            return "failed to spawn audio analysis process: Python 실행 파일을 찾을 수 없습니다 ('"
                    + head + "'). tools/audio-analysis 에 venv 가 없으면 다음을 실행하세요: "
                    + "`cd tools/audio-analysis && python3 -m venv .venv && source .venv/bin/activate"
                    + " && pip install -r requirements.txt`. 이후 audio.analysis.python-cmd"
                    + " (또는 AUDIO_ANALYSIS_PYTHON_CMD) 를 venv 의 python 절대 경로로 지정하세요.";
        }
        return "failed to spawn audio analysis process: " + head + " — 원인: " + causeMessage;
    }

    /** YouTube URL 원문은 INFO 이상 로깅 금지. host 만 노출 (Python 측 {@code mask_url} 와 정합). */
    private static String maskUrl(final String url) {
        if (url == null || url.isBlank()) {
            return "<blank>";
        }
        try {
            return java.net.URI.create(url).getHost();
        } catch (final IllegalArgumentException e) {
            return "<invalid>";
        }
    }
}
