package com.mobruji.song.application.catalogimport;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@link CatalogImportProperties} Bean Validation 회귀 — 잘못된 설정은 boot fail-fast 로 노출돼야 한다
 * (운영 중 NPE 가 아닌 startup 실패). 패턴: {@code AlbumCoverPropertiesValidationTest} 동일.
 */
class CatalogImportPropertiesValidationTest {

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
                    final CatalogImportProperties properties = context.getBean(CatalogImportProperties.class);
                    assertThat(properties.musicBrainzBaseUrl()).isEqualTo("https://musicbrainz.org/ws/2");
                    assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(properties.throttle()).isEqualTo(Duration.ofSeconds(1));
                    assertThat(properties.importConfidence()).isEqualTo(0.3);
                });
    }

    @Test
    @DisplayName("music-brainz-base-url 빈 문자열이면 @NotBlank 위반으로 startup fail")
    void invalidBaseUrl_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("catalog-import.music-brainz-base-url="))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("user-agent 빈 문자열이면 @NotBlank 위반으로 startup fail")
    void invalidUserAgent_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("catalog-import.user-agent="))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("request-timeout 누락이면 @NotNull 위반으로 startup fail")
    void missingRequestTimeout_failsStartup() {
        contextRunner
                .withPropertyValues(filterProps("catalog-import.request-timeout"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("request-timeout=0s 면 @DurationMin(1ms) 위반으로 startup fail")
    void zeroRequestTimeout_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("catalog-import.request-timeout=0s"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("throttle=0s 는 정상 기동 — 0 throttle 은 rate limit 무시지만 허용")
    void zeroThrottle_succeeds() {
        contextRunner
                .withPropertyValues(propsWithOverrides("catalog-import.throttle=0s"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CatalogImportProperties.class).throttle()).isEqualTo(Duration.ZERO);
                });
    }

    @Test
    @DisplayName("throttle=-1s 음수면 @DurationMin(0ms) 위반으로 startup fail")
    void negativeThrottle_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("catalog-import.throttle=-1s"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("import-confidence=1.5 범위 초과면 @DecimalMax 위반으로 startup fail")
    void importConfidenceOutOfRange_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("catalog-import.import-confidence=1.5"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    private static String[] validProps() {
        return new String[]{
                "catalog-import.music-brainz-base-url=https://musicbrainz.org/ws/2",
                "catalog-import.user-agent=mobruji-backend/0.1 (+test)",
                "catalog-import.request-timeout=5s",
                "catalog-import.throttle=1s",
                "catalog-import.import-confidence=0.3"
        };
    }

    private static String[] propsWithOverrides(final String... overrides) {
        final String[] base = validProps();
        final String[] merged = new String[base.length + overrides.length];
        System.arraycopy(base, 0, merged, 0, base.length);
        System.arraycopy(overrides, 0, merged, base.length, overrides.length);
        return merged;
    }

    private static String[] filterProps(final String keyPrefix) {
        return Arrays.stream(validProps())
                .filter(prop -> !prop.startsWith(keyPrefix))
                .toArray(String[]::new);
    }

    @EnableConfigurationProperties(CatalogImportProperties.class)
    static class TestConfig {
    }
}
