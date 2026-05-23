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
 * <p>의도: Python audio analysis tool 호출 설정이 누락/공백이면 컨텍스트 기동이 fail-fast 한다는 계약을 박제한다.
 * 또한 compact constructor 의 {@code dockerComposeService} 기본값 치환 ("audio-analysis") 분기가 회귀로
 * 제거되거나 약화되어도 본 테스트가 잡는다 — 운영 시 use-docker=true 인데 service 이름이 비어 있으면
 * docker compose 호출이 실패하므로 부팅 단계의 보호망이 중요하다.
 *
 * <p>레퍼런스: {@code CorsPropertiesValidationTest} (#640), {@code AlbumCoverPropertiesValidationTest} (#482),
 * {@code AdminAuthPropertiesValidationTest} (#637), {@code RecommendationPropertiesValidationTest} (#465 / #551).
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
    @DisplayName("timeout=0s 도 @NotNull 통과 — Duration 자체가 non-null 이면 binding 성공")
    void zeroDurationTimeout_succeeds() {
        // 회귀 가드: @NotNull 은 null 만 막는다. 0초 timeout 은 운영상 무의미하지만 boot 단계에서는 통과하며,
        // 추후 의미론적 검증 (@DurationMin 등) 을 추가하면 본 테스트가 fail 하면서 새 가드를 강제하게 된다.
        contextRunner
                .withPropertyValues(propsWithOverrides("audio.analysis.timeout=0s"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final AudioAnalysisProperties properties = context.getBean(AudioAnalysisProperties.class);
                    assertThat(properties.timeout()).isEqualTo(Duration.ZERO);
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
