package com.mobruji.song.application.albumcover;

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
 * {@link AlbumCoverProperties} Bean Validation 동작 회귀.
 *
 * <p>의도: 잘못된 외부 설정 값이 바인딩되면 컨텍스트 기동이 fail-fast 한다는 것을 확인한다.
 * iTunes Search API 호출 settings 는 모두 환경변수 override 대상이고, 누락/오타 시 운영 중 NPE 가 아닌
 * boot fail 로 노출되어야 한다 — 본 테스트가 그 계약을 박제한다.
 *
 * <p>패턴: {@code RecommendationPropertiesValidationTest} 동일.
 */
class AlbumCoverPropertiesValidationTest {

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
                    final AlbumCoverProperties properties = context.getBean(AlbumCoverProperties.class);
                    assertThat(properties.itunes().baseUrl()).isEqualTo("https://itunes.apple.com/search");
                    assertThat(properties.itunes().country()).isEqualTo("KR");
                    assertThat(properties.itunes().requestTimeout()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(properties.itunes().throttle()).isEqualTo(Duration.ofSeconds(1));
                    assertThat(properties.itunes().thumbResolution()).isEqualTo("600x600");
                });
    }

    @Test
    @DisplayName("itunes.baseUrl 빈 문자열이면 @NotBlank 위반으로 startup fail")
    void invalidBaseUrl_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("album-cover.itunes.base-url="))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("itunes.country 빈 문자열이면 @NotBlank 위반으로 startup fail")
    void invalidCountry_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("album-cover.itunes.country="))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("itunes.thumbResolution 빈 문자열이면 @NotBlank 위반으로 startup fail")
    void invalidThumbResolution_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("album-cover.itunes.thumb-resolution="))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("itunes.requestTimeout 누락이면 @NotNull 위반으로 startup fail")
    void missingRequestTimeout_failsStartup() {
        final String[] filtered = filterProps("album-cover.itunes.request-timeout");
        contextRunner
                .withPropertyValues(filtered)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("itunes.throttle 누락이면 @NotNull 위반으로 startup fail")
    void missingThrottle_failsStartup() {
        final String[] filtered = filterProps("album-cover.itunes.throttle");
        contextRunner
                .withPropertyValues(filtered)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("itunes nested 자체가 누락이면 @NotNull 위반으로 startup fail")
    void missingItunesNested_failsStartup() {
        // 모든 itunes.* prefix 제거 → outer record 의 @NotNull Itunes itunes 위반.
        final String[] filtered = filterProps("album-cover.itunes.");
        contextRunner
                .withPropertyValues(filtered)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("itunes.requestTimeout=0s 도 @NotNull 통과 — Duration 자체가 non-null 이면 binding 성공")
    void zeroDurationRequestTimeout_succeeds() {
        // 회귀 가드: @NotNull 은 null 만 막는다. 0 초 timeout 이 의도되지 않더라도 boot 단계에서는 통과하며,
        // 추후 의미론적 검증 (@DurationMin 등) 을 추가하면 본 테스트가 fail 하면서 새 가드를 강제하게 된다.
        contextRunner
                .withPropertyValues(propsWithOverrides("album-cover.itunes.request-timeout=0s"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final AlbumCoverProperties properties = context.getBean(AlbumCoverProperties.class);
                    assertThat(properties.itunes().requestTimeout()).isEqualTo(Duration.ZERO);
                });
    }

    @Test
    @DisplayName("itunes.throttle=0s 도 @NotNull 통과 — 0 throttle 도 binding 단계에서는 정상")
    void zeroDurationThrottle_succeeds() {
        // 회귀 가드: 0 throttle 은 운영상 위험하지만 boot fail 대상은 아니다. 의미론적 검증 추가 시 본 테스트 fail.
        contextRunner
                .withPropertyValues(propsWithOverrides("album-cover.itunes.throttle=0s"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final AlbumCoverProperties properties = context.getBean(AlbumCoverProperties.class);
                    assertThat(properties.itunes().throttle()).isEqualTo(Duration.ZERO);
                });
    }

    @Test
    @DisplayName("itunes.country=US 등 다른 country code 도 정상 바인딩 (KR 외 override 가능 박제)")
    void alternateCountry_binds() {
        contextRunner
                .withPropertyValues(propsWithOverrides("album-cover.itunes.country=US"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final AlbumCoverProperties properties = context.getBean(AlbumCoverProperties.class);
                    assertThat(properties.itunes().country()).isEqualTo("US");
                });
    }

    private static String[] validProps() {
        return new String[]{
                "album-cover.itunes.base-url=https://itunes.apple.com/search",
                "album-cover.itunes.country=KR",
                "album-cover.itunes.request-timeout=5s",
                "album-cover.itunes.throttle=1s",
                "album-cover.itunes.thumb-resolution=600x600"
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
     * 정확한 key 비교가 아닌 prefix 매치인 이유: {@code album-cover.itunes.} 같이 nested 전체를 한 번에 제거하기 위함.
     */
    private static String[] filterProps(final String keyPrefix) {
        final String[] base = validProps();
        return java.util.Arrays.stream(base)
                .filter(prop -> !prop.startsWith(keyPrefix))
                .toArray(String[]::new);
    }

    @EnableConfigurationProperties(AlbumCoverProperties.class)
    static class TestConfig {
    }
}
