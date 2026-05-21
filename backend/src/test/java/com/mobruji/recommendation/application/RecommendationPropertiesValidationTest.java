package com.mobruji.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@link RecommendationProperties} Bean Validation 동작 회귀.
 *
 * <p>의도: 잘못된 외부 설정 값이 바인딩되면 컨텍스트 기동이 fail-fast 한다는 것을 확인한다. PR #54 후속 — 검증 방식을
 * {@code Objects.requireNonNull}/{@code IllegalArgumentException}에서 {@link jakarta.validation.constraints} 어노테이션으로
 * 통일한 효과를 단언한다.
 */
class RecommendationPropertiesValidationTest {

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
                    final RecommendationProperties properties = context.getBean(RecommendationProperties.class);
                    assertThat(properties.resultCount()).isEqualTo(10);
                    assertThat(properties.weights().voiceFit()).isEqualTo(0.5);
                });
    }

    @Test
    @DisplayName("resultCount=0 이면 @Min(1) 위반으로 startup fail")
    void invalidResultCount_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("recommendation.result-count=0"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("jitterMagnitude 음수면 @DecimalMin(0.0) 위반으로 startup fail")
    void invalidJitterMagnitude_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("recommendation.jitter-magnitude=-0.1"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("weights.voiceFit 음수면 nested @DecimalMin(0.0) 위반으로 startup fail")
    void invalidWeight_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("recommendation.weights.voice-fit=-0.1"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("diversity.maxSameArtist=0 이면 nested @Min(1) 위반으로 startup fail")
    void invalidDiversity_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("recommendation.diversity.max-same-artist=0"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    private static String[] validProps() {
        return new String[]{
                "recommendation.weights.voice-fit=0.5",
                "recommendation.weights.genre=0.2",
                "recommendation.weights.mood=0.2",
                "recommendation.weights.popularity=0.1",
                "recommendation.weights.tempo-match=0.1",
                "recommendation.diversity.max-same-artist=2",
                "recommendation.diversity.max-same-genre=4",
                "recommendation.tempo.distance-tolerance=40",
                "recommendation.tempo.mood-default-bpm.UPBEAT=128",
                "recommendation.tempo.mood-default-bpm.CALM=70",
                "recommendation.tempo.fallback-bpm=110",
                "recommendation.result-count=10",
                "recommendation.jitter-magnitude=0.01",
                "recommendation.seed-strategy=derived"
        };
    }

    private static String[] propsWithOverrides(final String... overrides) {
        final String[] base = validProps();
        final String[] merged = new String[base.length + overrides.length];
        System.arraycopy(base, 0, merged, 0, base.length);
        System.arraycopy(overrides, 0, merged, base.length, overrides.length);
        return merged;
    }

    @EnableConfigurationProperties(RecommendationProperties.class)
    static class TestConfig {
    }
}
