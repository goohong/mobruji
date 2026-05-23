package com.mobruji.song.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@link AudioAnalysisProperties} Bean Validation + compact constructor 회귀 (#644).
 *
 * <p>의도: Python audio analysis tool 호출 설정이 누락/공백이면 컨텍스트 기동이 fail-fast 한다는 계약,
 * 그리고 compact constructor 의 {@code dockerComposeService} 기본값 치환 ("audio-analysis") 분기가 회귀로
 * 제거되어도 잡히도록 박제한다. 패턴 레퍼런스: {@code CorsPropertiesValidationTest} / {@code AlbumCoverPropertiesValidationTest}.
 */
class AudioAnalysisPropertiesValidationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    @DisplayName("유효한 값으로 바인딩 시 컨텍스트가 정상 기동한다")
    void validBinding_succeeds() {
        contextRunner
                .withPropertyValues(validProps())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final AudioAnalysisProperties properties = context.getBean(AudioAnalysisProperties.class);
                    assertThat(properties.pythonCmd()).isEqualTo("python3");
                    assertThat(properties.toolDir()).isEqualTo("../tools/audio-analysis");
                    assertThat(properties.timeout()).isEqualTo(Duration.ofSeconds(60));
                    assertThat(properties.useDocker()).isFalse();
                    assertThat(properties.dockerComposeFile()).isEqualTo("../docker-compose.audio.yml");
                    assertThat(properties.dockerComposeService()).isEqualTo("audio-analysis");
                });
    }

    @Test
    @DisplayName("python-cmd 빈 문자열이면 @NotBlank 위반으로 startup fail")
    void blankPythonCmd_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("audio.analysis.python-cmd="))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("tool-dir 빈 문자열이면 @NotBlank 위반으로 startup fail")
    void blankToolDir_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("audio.analysis.tool-dir="))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("timeout 누락이면 @NotNull 위반으로 startup fail")
    void missingTimeout_failsStartup() {
        final String[] filtered = filterProps("audio.analysis.timeout");
        contextRunner
                .withPropertyValues(filtered)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("docker-compose-service 빈 문자열이면 compact constructor 가 기본값 'audio-analysis' 로 치환")
    void blankDockerComposeService_replacedWithDefault() {
        // 회귀 가드: compact constructor 의 blank 분기 (`if (blank) -> "audio-analysis"`) 가 제거되면 본 테스트가 fail.
        contextRunner
                .withPropertyValues(propsWithOverrides("audio.analysis.docker-compose-service="))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final AudioAnalysisProperties properties = context.getBean(AudioAnalysisProperties.class);
                    assertThat(properties.dockerComposeService()).isEqualTo("audio-analysis");
                });
    }

    @Test
    @DisplayName("docker-compose-service key 자체 누락이면 compact constructor 가 기본값 'audio-analysis' 로 치환")
    void missingDockerComposeService_replacedWithDefault() {
        // 회귀 가드: compact constructor 의 null 분기. application.yml 의 ${...:audio-analysis} placeholder 와는
        // 독립적으로, properties 객체 단의 기본값 안전망이 살아있어야 한다.
        final String[] filtered = filterProps("audio.analysis.docker-compose-service");
        contextRunner
                .withPropertyValues(filtered)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final AudioAnalysisProperties properties = context.getBean(AudioAnalysisProperties.class);
                    assertThat(properties.dockerComposeService()).isEqualTo("audio-analysis");
                });
    }

    @Test
    @DisplayName("docker-compose-service custom 값은 그대로 보존")
    void customDockerComposeService_preserved() {
        contextRunner
                .withPropertyValues(propsWithOverrides("audio.analysis.docker-compose-service=custom-svc"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final AudioAnalysisProperties properties = context.getBean(AudioAnalysisProperties.class);
                    assertThat(properties.dockerComposeService()).isEqualTo("custom-svc");
                });
    }

    @Test
    @DisplayName("use-docker=true override 가 정상 바인딩")
    void useDockerTrue_binds() {
        contextRunner
                .withPropertyValues(propsWithOverrides("audio.analysis.use-docker=true"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final AudioAnalysisProperties properties = context.getBean(AudioAnalysisProperties.class);
                    assertThat(properties.useDocker()).isTrue();
                });
    }

    @Test
    @DisplayName("timeout=0s 면 @DurationMin(1s) 위반으로 startup fail (#653)")
    void zeroDurationTimeout_failsStartup() {
        // 회귀 가드: 0초 timeout 은 외부 process 가 즉시 destroyForcibly 되어 운영상 무의미.
        // #653 에서 @DurationMin 으로 의미론 가드 도입.
        contextRunner
                .withPropertyValues(propsWithOverrides("audio.analysis.timeout=0s"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("timeout=-1s 음수면 @DurationMin(1s) 위반으로 startup fail (#653)")
    void negativeDurationTimeout_failsStartup() {
        // 회귀 가드: 음수 timeout 은 정의 불가. 새 의미론 가드가 누락되거나 완화되면 본 테스트 fail.
        contextRunner
                .withPropertyValues(propsWithOverrides("audio.analysis.timeout=-1s"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("timeout=1s 정확한 하한, 정상 기동 (#653 boundary)")
    void timeoutAtMin_succeeds() {
        contextRunner
                .withPropertyValues(propsWithOverrides("audio.analysis.timeout=1s"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final AudioAnalysisProperties properties = context.getBean(AudioAnalysisProperties.class);
                    assertThat(properties.timeout()).isEqualTo(Duration.ofSeconds(1));
                });
    }

    @Test
    @DisplayName("python-cmd key 자체 누락이면 @NotBlank 위반으로 startup fail")
    void missingPythonCmd_failsStartup() {
        final String[] filtered = filterProps("audio.analysis.python-cmd");
        contextRunner
                .withPropertyValues(filtered)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    private static String[] validProps() {
        return new String[]{
                "audio.analysis.python-cmd=python3",
                "audio.analysis.tool-dir=../tools/audio-analysis",
                "audio.analysis.timeout=60s",
                "audio.analysis.use-docker=false",
                "audio.analysis.docker-compose-file=../docker-compose.audio.yml",
                "audio.analysis.docker-compose-service=audio-analysis"
        };
    }

    private static String[] propsWithOverrides(final String... overrides) {
        final String[] base = validProps();
        final String[] merged = new String[base.length + overrides.length];
        System.arraycopy(base, 0, merged, 0, base.length);
        System.arraycopy(overrides, 0, merged, base.length, overrides.length);
        return merged;
    }

    /**
     * {@link #validProps()} 에서 key 가 {@code keyPrefix} 로 시작하는 항목을 제거한다.
     * prefix 매치 — 단일 키 (정확 일치도 prefix 로 자연 매칭됨) 와 nested 전체 제거 모두 커버.
     */
    private static String[] filterProps(final String keyPrefix) {
        final String[] base = validProps();
        return java.util.Arrays.stream(base)
                .filter(prop -> !prop.startsWith(keyPrefix))
                .toArray(String[]::new);
    }

    @EnableConfigurationProperties(AudioAnalysisProperties.class)
    static class TestConfig {
    }
}
