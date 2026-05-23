package com.mobruji.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@link CorsProperties} Bean Validation 동작 회귀 (#640).
 *
 * <p>의도: CORS 허용 origin 설정이 누락/공백이면 컨텍스트가 fail-fast 한다는 계약을 가드한다.
 * CorsProperties 클래스 주석은 "비어 있으면 부팅 fail-fast — 의도치 않은 전체 차단 또는
 * 와일드카드 노출을 방지" 라고 명시하지만, {@code @Validated} 또는 {@code @NotEmpty} 가 누락/완화되어도
 * 기존 통합 테스트로는 잡히지 않는다. 보안 경계를 지키기 위한 회귀 가드.
 *
 * <p>레퍼런스: {@code AdminAuthPropertiesValidationTest} (#637),
 * {@code AlbumCoverPropertiesValidationTest} (#482),
 * {@code RecommendationPropertiesValidationTest} (#465 / #551).
 */
class CorsPropertiesValidationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    @DisplayName("유효한 단일 origin 으로 바인딩 시 컨텍스트가 정상 기동한다")
    void singleOrigin_succeeds() {
        contextRunner
                .withPropertyValues("mobruji.cors.allowed-origins=http://localhost:3000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final CorsProperties corsProperties = context.getBean(CorsProperties.class);
                    assertThat(corsProperties.allowedOrigins()).containsExactly("http://localhost:3000");
                });
    }

    @Test
    @DisplayName("쉼표 구분 멀티 origin 도 정상 리스트 바인딩")
    void multipleOrigins_succeeds() {
        contextRunner
                .withPropertyValues(
                        "mobruji.cors.allowed-origins=http://localhost:3000,https://mobruji.com")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final CorsProperties corsProperties = context.getBean(CorsProperties.class);
                    assertThat(corsProperties.allowedOrigins())
                            .containsExactly("http://localhost:3000", "https://mobruji.com");
                });
    }

    @Test
    @DisplayName("allowed-origins 키 자체 누락 → @NotEmpty 위반으로 startup fail")
    void missingAllowedOrigins_failsStartup() {
        contextRunner
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("allowed-origins= (빈 값) → 빈 리스트로 바인딩되어 @NotEmpty 위반으로 startup fail")
    void emptyAllowedOrigins_failsStartup() {
        contextRunner
                .withPropertyValues("mobruji.cors.allowed-origins=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("3개 이상 origin 도 모두 리스트 보존 (배열 boundary)")
    void threeOrigins_succeeds() {
        contextRunner
                .withPropertyValues(
                        "mobruji.cors.allowed-origins=http://a.test,http://b.test,http://c.test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final CorsProperties corsProperties = context.getBean(CorsProperties.class);
                    assertThat(corsProperties.allowedOrigins())
                            .containsExactly("http://a.test", "http://b.test", "http://c.test");
                });
    }

    @Test
    @DisplayName("custom port 포함한 origin 도 정상 바인딩 (boundary)")
    void customPortOrigin_succeeds() {
        contextRunner
                .withPropertyValues("mobruji.cors.allowed-origins=http://127.0.0.1:8080")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final CorsProperties corsProperties = context.getBean(CorsProperties.class);
                    assertThat(corsProperties.allowedOrigins()).containsExactly("http://127.0.0.1:8080");
                });
    }

    @EnableConfigurationProperties(CorsProperties.class)
    static class TestConfig {
    }
}
