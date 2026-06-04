package com.mobruji.user.api.dto;

import java.time.LocalDateTime;

import com.mobruji.user.application.AuthResult;

/**
 * 회원가입/로그인 응답 — 인증 토큰(원문, 1회 노출) + 만료 시각 + 사용자 식별. 비밀번호 해시는 절대
 * 포함하지 않는다.
 */
public record AuthResponse(
        Long userId,
        String email,
        String token,
        LocalDateTime tokenExpiresAt
) {

    public static AuthResponse from(final AuthResult authResult) {
        return new AuthResponse(
                authResult.user().getId(),
                authResult.user().getEmail(),
                authResult.token().rawToken(),
                authResult.token().expiresAt());
    }
}
