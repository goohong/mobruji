package com.mobruji.user.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.mobruji.user.domain.AnonymousSession;

/**
 * {@code POST /api/v1/sessions/rotate} 요청 body.
 *
 * <p>spec: {@code docs/features/anonymous-session-lifecycle.md} §5-4.
 *
 * <ul>
 * <li>{@code currentSessionId}: 회전 전 sessionId. 헤더 {@code X-Session-Id} 와 일치해야 함
 * ({@link com.mobruji.user.application.SessionAuthGuard} 가 검증). UUIDv4 포맷 강제 — 임의 문자열 거부.</li>
 * <li>{@code dataMode}: 데이터 처리 모드. v0.3 은 {@code DELETE} 만 지원 — {@code ANONYMIZE} 는
 * v0.4 후속. nullable 허용 (default DELETE).</li>
 * </ul>
 */
public record SessionRotateRequest(
        @NotBlank @Size(max = AnonymousSession.SESSION_ID_MAX_LENGTH) @Pattern(
                regexp = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", message = "UUIDv4 format required"
        ) String currentSessionId,
        SessionDataMode dataMode
) {

    public SessionDataMode resolveDataMode() {
        return dataMode == null ? SessionDataMode.DELETE : dataMode;
    }
}
