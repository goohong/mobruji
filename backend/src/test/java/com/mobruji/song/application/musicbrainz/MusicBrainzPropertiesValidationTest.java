package com.mobruji.song.application.musicbrainz;

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
 * {@link MusicBrainzProperties} Bean Validation 회귀 — 잘못된 외부 설정이 바인딩되면 boot fail-fast 한다.
 *
 * <p>약관상 의무 값({@code user-agent} contact / 1 req/s {@code throttle} 음수 차단 / 503 backoff 음수 차단)이
 * 운영 중 NPE 가 아닌 startup fail 로 노출되는 계약을 박제한다.
 */
class MusicBrainzPropertiesValidationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    @DisplayName("유효한 값이면 정상 기동")
    void validBinding_succeeds() {
        contextRunner
                .withPropertyValues(validProps())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final MusicBrainzProperties properties = context.getBean(MusicBrainzProperties.class);
                    assertThat(properties.baseUrl()).isEqualTo("https://musicbrainz.org/ws/2");
                    assertThat(properties.userAgent()).isEqualTo("mobruji-backend/0.1 (+test)");
                    assertThat(properties.throttle()).isEqualTo(Duration.ofMillis(1100));
                    assertThat(properties.minScore()).isEqualTo(90);
                    assertThat(properties.searchLimit()).isEqualTo(5);
                    assertThat(properties.backfill().batchSize()).isEqualTo(30);
                    assertThat(properties.backfill().enabled()).isFalse();
                });
    }

    @Test
    @DisplayName("user-agent 빈 문자열이면 @NotBlank 위반 startup fail (약관상 contact 의무)")
    void blankUserAgent_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("musicbrainz.user-agent="))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("base-url 빈 문자열이면 @NotBlank 위반 startup fail")
    void blankBaseUrl_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("musicbrainz.base-url="))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("request-timeout=0s 면 @DurationMin(1ms) 위반 startup fail")
    void zeroRequestTimeout_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("musicbrainz.request-timeout=0s"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("throttle=-1s 음수면 @DurationMin(0) 위반 startup fail")
    void negativeThrottle_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("musicbrainz.throttle=-1s"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("throttle=0s 는 정상 기동 (rate limit 무시지만 허용, 음수만 차단)")
    void zeroThrottle_succeeds() {
        contextRunner
                .withPropertyValues(propsWithOverrides("musicbrainz.throttle=0s"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(MusicBrainzProperties.class).throttle()).isEqualTo(Duration.ZERO);
                });
    }

    @Test
    @DisplayName("min-score=101 이면 @Max(100) 위반 startup fail")
    void minScoreAboveMax_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("musicbrainz.min-score=101"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("search-limit=0 이면 @Min(1) 위반 startup fail")
    void searchLimitZero_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("musicbrainz.search-limit=0"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("backfill.batch-size=0 이면 @Min(1) 위반 startup fail")
    void batchSizeZero_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("musicbrainz.backfill.batch-size=0"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    private static String[] validProps() {
        return new String[]{
                "musicbrainz.base-url=https://musicbrainz.org/ws/2",
                "musicbrainz.user-agent=mobruji-backend/0.1 (+test)",
                "musicbrainz.request-timeout=5s",
                "musicbrainz.throttle=1100ms",
                "musicbrainz.max-retries=3",
                "musicbrainz.backoff=1s",
                "musicbrainz.min-score=90",
                "musicbrainz.search-limit=5",
                "musicbrainz.backfill.batch-size=30",
                "musicbrainz.backfill.enabled=false"
        };
    }

    private static String[] propsWithOverrides(final String... overrides) {
        final String[] base = validProps();
        final String[] merged = new String[base.length + overrides.length];
        System.arraycopy(base, 0, merged, 0, base.length);
        System.arraycopy(overrides, 0, merged, base.length, overrides.length);
        return merged;
    }

    @EnableConfigurationProperties(MusicBrainzProperties.class)
    static class TestConfig {
    }
}
