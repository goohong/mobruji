package com.mobruji.song.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Python audio analysis tool ({@code tools/audio-analysis/analyze.py}) 호출 설정.
 *
 * <p>spec: {@code docs/features/audio-tooling-bootstrap.md} §3 비기능 — 곡당 30초 이내, 60초 timeout 여유.
 *
 * <ul>
 * <li>{@code pythonCmd}: 실행 명령. 로컬 venv 사용 시 `/path/to/.venv/bin/python` 으로 override</li>
 * <li>{@code toolDir}: analyze.py 가 위치한 디렉터리. 컨테이너/CI 환경별로 override</li>
 * <li>{@code timeout}: 외부 process 최대 대기 시간. 초과 시 destroyForcibly + 실패</li>
 * <li>{@code useDocker}: true 시 호스트 Python 대신 {@code docker compose -f docker-compose.audio.yml run}
 * 을 통해 컨테이너에서 실행. 기본 false (호스트 Python).</li>
 * <li>{@code dockerComposeFile}: docker compose 파일 경로 (use-docker=true 일 때만 사용).</li>
 * <li>{@code dockerComposeService}: docker compose service 이름 (기본 {@code audio-analysis}).</li>
 * </ul>
 *
 * <p>운영 환경에서는 {@code application-prod.yml} 또는 환경변수
 * ({@code AUDIO_ANALYSIS_TOOL_DIR}, {@code AUDIO_ANALYSIS_PYTHON_CMD}) 로 주입한다.
 */
@Validated
@ConfigurationProperties(prefix = "audio.analysis")
public record AudioAnalysisProperties(
        @NotBlank String pythonCmd,
        @NotBlank String toolDir,
        @NotNull Duration timeout,
        boolean useDocker,
        String dockerComposeFile,
        String dockerComposeService
) {
    public AudioAnalysisProperties {
        // dockerComposeService 기본값. record 의 compact constructor 에서 null 이면 기본값 채움.
        if (dockerComposeService == null || dockerComposeService.isBlank()) {
            dockerComposeService = "audio-analysis";
        }
    }
}
