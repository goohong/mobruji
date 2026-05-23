package com.mobruji.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@link AdminAuthProperties} Bean Validation 동작 회귀 (#635 / PR #631 후속).
 *
 * <p>의도: admin endpoint 임시 토큰 게이트가 외부 설정 누락/빈값 시 컨텍스트 기동을 fail-fast 한다는 것을 확인한다.
 * {@code AdminTokenVerifierTest} 는 verifier 분기만 커버하므로, 보안 클래스 주석에 적힌 "blank/null 이면 부팅
 * fail-fast" 계약 자체를 회귀 가드한다 — admin endpoint가 보호 없이 노출되는 사고를 막는다.
 *
 * <p>레퍼런스: {@code RecommendationPropertiesValidationTest},
 * {@code AlbumCoverPropertiesValidationTest}.
 */
class AdminAuthPropertiesValidationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    @DisplayName("유효한 token 으로 바인딩 시 컨텍스트가 정상 기동한다")
    void validToken_succeeds() {
        contextRunner
                .withPropertyValues("mobruji.admin.token=super-secret-admin-token")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final AdminAuthProperties properties = context.getBean(AdminAuthProperties.class);
                    assertThat(properties.token()).isEqualTo("super-secret-admin-token");
                });
    }

    @Test
    @DisplayName("token 키 자체 누락 → @NotBlank 위반으로 startup fail")
    void missingToken_failsStartup() {
        contextRunner
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("token=\"\" (빈 문자열) → @NotBlank 위반으로 startup fail")
    void blankToken_failsStartup() {
        contextRunner
                .withPropertyValues("mobruji.admin.token=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("token 이 whitespace 만 → @NotBlank 위반으로 startup fail")
    void whitespaceOnlyToken_failsStartup() {
        contextRunner
                .withPropertyValues("mobruji.admin.token=   ")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("token=tab/space 혼합 whitespace → @NotBlank 위반으로 startup fail")
    void mixedWhitespaceToken_failsStartup() {
        contextRunner
                .withPropertyValues("mobruji.admin.token=\t \t")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("UTF-8 (한글) token 도 정상 바인딩 — non-ASCII 가 @NotBlank 를 통과한다")
    void utf8Token_succeeds() {
        contextRunner
                .withPropertyValues("mobruji.admin.token=관리자-토큰-가나다")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final AdminAuthProperties properties = context.getBean(AdminAuthProperties.class);
                    assertThat(properties.token()).isEqualTo("관리자-토큰-가나다");
                });
    }

    @EnableConfigurationProperties(AdminAuthProperties.class)
    static class TestConfig {
    }
}
