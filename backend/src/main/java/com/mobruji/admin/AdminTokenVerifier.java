package com.mobruji.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code X-Admin-Token} 헤더 값을 {@link AdminAuthProperties#token()} 과 상수시간 비교한다.
 *
 * <p>spec: v0.3 P0 admin endpoint 게이트 (#224 #228).
 *
 * <ul>
 * <li>헤더 누락/blank → 401</li>
 * <li>토큰 불일치 → 401</li>
 * <li>일치 → 통과 (반환값 없음)</li>
 * </ul>
 *
 * <p>토큰 자체는 로그/예외 메시지에 절대 포함하지 않는다. timing attack 회피용으로
 * {@link MessageDigest#isEqual(byte[], byte[])} 의 상수시간 비교를 사용한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminTokenVerifier {

    private static final String HEADER_NAME = "X-Admin-Token";

    private final AdminAuthProperties adminAuthProperties;

    public void verify(final String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) {
            log.warn("admin endpoint 접근 거부 — {} 헤더 누락", HEADER_NAME);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing admin token");
        }
        final byte[] expected = adminAuthProperties.token().getBytes(StandardCharsets.UTF_8);
        final byte[] presented = presentedToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, presented)) {
            log.warn("admin endpoint 접근 거부 — {} 헤더 값 불일치", HEADER_NAME);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid admin token");
        }
    }
}
