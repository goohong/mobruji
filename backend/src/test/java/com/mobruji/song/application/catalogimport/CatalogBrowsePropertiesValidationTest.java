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
 * {@link CatalogBrowseProperties} Bean Validation 회귀 — 잘못된 설정은 boot fail-fast 로 노출돼야 한다
 * (운영 중 NPE 가 아닌 startup 실패). 패턴: {@code CatalogImportPropertiesValidationTest} 동일.
 */
class CatalogBrowsePropertiesValidationTest {

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
                    final CatalogBrowseProperties properties = context.getBean(CatalogBrowseProperties.class);
                    assertThat(properties.musicBrainzBaseUrl()).isEqualTo("https://musicbrainz.org/ws/2");
                    assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(properties.throttle()).isEqualTo(Duration.ofMillis(1100));
                    assertThat(properties.pageSize()).isEqualTo(100);
                    assertThat(properties.maxArtists()).isEqualTo(10);
                    assertThat(properties.maxRecordingsPerArtist()).isEqualTo(50);
                    assertThat(properties.importConfidence()).isEqualTo(0.3);
                });
    }

    @Test
    @DisplayName("music-brainz-base-url 빈 문자열이면 @NotBlank 위반으로 startup fail")
    void invalidBaseUrl_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("catalog-browse.music-brainz-base-url="))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("user-agent 빈 문자열이면 @NotBlank 위반으로 startup fail")
    void invalidUserAgent_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("catalog-browse.user-agent="))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("request-timeout 누락이면 @NotNull 위반으로 startup fail")
    void missingRequestTimeout_failsStartup() {
        contextRunner
                .withPropertyValues(filterProps("catalog-browse.request-timeout"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("request-timeout=0s 면 @DurationMin(1ms) 위반으로 startup fail")
    void zeroRequestTimeout_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("catalog-browse.request-timeout=0s"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("throttle=0s 는 정상 기동 — 0 throttle 은 rate limit 무시지만 허용")
    void zeroThrottle_succeeds() {
        contextRunner
                .withPropertyValues(propsWithOverrides("catalog-browse.throttle=0s"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CatalogBrowseProperties.class).throttle()).isEqualTo(Duration.ZERO);
                });
    }

    @Test
    @DisplayName("throttle=-1s 음수면 @DurationMin(0ms) 위반으로 startup fail")
    void negativeThrottle_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("catalog-browse.throttle=-1s"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("max-retries=-1 음수면 @Min(0) 위반으로 startup fail")
    void negativeMaxRetries_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("catalog-browse.max-retries=-1"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("page-size=0 이면 @Min(1) 위반으로 startup fail")
    void zeroPageSize_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("catalog-browse.page-size=0"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("max-artists=0 이면 @Min(1) 위반으로 startup fail")
    void zeroMaxArtists_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("catalog-browse.max-artists=0"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("max-recordings-per-artist=0 이면 @Min(1) 위반으로 startup fail")
    void zeroMaxRecordingsPerArtist_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("catalog-browse.max-recordings-per-artist=0"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("import-confidence=1.5 범위 초과면 @DecimalMax 위반으로 startup fail")
    void importConfidenceOutOfRange_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("catalog-browse.import-confidence=1.5"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    private static String[] validProps() {
        return new String[]{
                "catalog-browse.music-brainz-base-url=https://musicbrainz.org/ws/2",
                "catalog-browse.user-agent=mobruji-backend/0.1 (+test)",
                "catalog-browse.request-timeout=5s",
                "catalog-browse.throttle=1100ms",
                "catalog-browse.backoff=1s",
                "catalog-browse.max-retries=3",
                "catalog-browse.page-size=100",
                "catalog-browse.max-artists=10",
                "catalog-browse.max-recordings-per-artist=50",
                "catalog-browse.import-confidence=0.3"
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

    @EnableConfigurationProperties(CatalogBrowseProperties.class)
    static class TestConfig {
    }
}
