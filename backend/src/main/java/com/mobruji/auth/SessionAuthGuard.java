package com.mobruji.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import lombok.extern.slf4j.Slf4j;

/**
 * 세션 귀속(session-bound) endpoint 인증 가드.
 *
 * <p>spec rev 16(#238): {@code GET /api/v1/sessions/{id}/voice-range-history},
 * {@code GET /api/v1/sessions/{id}/recommendation-history} 등 path 의 {@code sessionId}
 * 가 호출자 본인의 세션인지 검증한다. Spring Security 정식 도입 전까지의 임시 게이트.
 *
 * <p>인증 메커니즘: HTTP 헤더 {@code X-Session-Id} 로 호출자 sessionId 를 받아 path 변수와
 * 상수시간 비교한다. spec
 * {@code docs/features/recommendation-history-and-feedback.md §5-2} 에 명시된
 * "sessionId 쿠키 또는 헤더" 중 헤더 방식을 우선 채택 — 쿠키는 추후 sessionId TTL/회전 정책(#209)
 * 도입과 함께 별도 ADR 로 다룬다.
 *
 * <ul>
 * <li>헤더 누락/blank → 401</li>
 * <li>path sessionId 와 헤더 값 불일치 → 401</li>
 * <li>일치 → 통과 (반환값 없음)</li>
 * </ul>
 *
 * <p>sessionId 원문은 로그/예외 메시지/응답에 절대 포함하지 않는다
 * ({@code docs/ai-harness/04-security-policy.md} 익명 세션 룰).
 * timing attack 회피용으로 {@link MessageDigest#isEqual(byte[], byte[])} 의 상수시간 비교를
 * 사용한다 (admin gate #229 동일 패턴).
 */
@Slf4j
@Component
public class SessionAuthGuard {

    private static final String HEADER_NAME = "X-Session-Id";

    public void verify(final String pathSessionId, final String presentedSessionId) {
        if (pathSessionId == null || pathSessionId.isBlank()) {
            // path 가 비어있는 경우는 매핑 단계에서 사실상 차단되지만 방어적으로 막는다.
            log.warn("session-bound endpoint 접근 거부 — path sessionId 누락");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing session id");
        }
        if (presentedSessionId == null || presentedSessionId.isBlank()) {
            log.warn("session-bound endpoint 접근 거부 — {} 헤더 누락", HEADER_NAME);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing session id");
        }
        final byte[] expected = pathSessionId.getBytes(StandardCharsets.UTF_8);
        final byte[] presented = presentedSessionId.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, presented)) {
            log.warn("session-bound endpoint 접근 거부 — path/{} 헤더 불일치", HEADER_NAME);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "session id mismatch");
        }
    }
}
