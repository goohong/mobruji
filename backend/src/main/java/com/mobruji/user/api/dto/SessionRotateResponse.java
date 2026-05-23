package com.mobruji.user.api.dto;

/**
 * {@code POST /api/v1/sessions/rotate} 응답 body.
 *
 * <p>spec: {@code docs/features/anonymous-session-lifecycle.md} §5-4.
 */
public record SessionRotateResponse(
        String newSessionId
) {
}
