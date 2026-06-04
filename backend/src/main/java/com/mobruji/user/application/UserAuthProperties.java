package com.mobruji.user.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 사용자 인증 토큰 정책 설정.
 *
 * <p>spec: 인증·음역대 프로필 영속 (#1491).
 *
 * <ul>
 * <li>{@code tokenTtl}: 발급 토큰 수명 (default 30일). 만료 후 재로그인 필요.</li>
 * </ul>
 *
 * <p>환경변수 {@code MOBRUJI_AUTH_TOKEN_TTL} 로 override 가능. 미지정 시 코드 default fallback.
 */
@ConfigurationProperties(prefix = "mobruji.auth")
public record UserAuthProperties(
        Duration tokenTtl
) {

    /** default — 30일. */
    public static final Duration DEFAULT_TOKEN_TTL = Duration.ofDays(30);

    public UserAuthProperties {
        if (tokenTtl == null) {
            tokenTtl = DEFAULT_TOKEN_TTL;
        }
        if (tokenTtl.isNegative() || tokenTtl.isZero()) {
            throw new IllegalArgumentException("mobruji.auth.token-ttl must be > 0: " + tokenTtl);
        }
    }
}
