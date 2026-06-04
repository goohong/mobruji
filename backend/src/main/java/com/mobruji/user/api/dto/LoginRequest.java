package com.mobruji.user.api.dto;

import jakarta.validation.constraints.NotBlank;

import com.mobruji.user.application.LoginCommand;

/**
 * {@code POST /api/v1/users/login} 요청 body. 형식 검증은 회원가입보다 느슨하게(존재만) — 실제
 * 일치 여부는 {@code UserAccountService} 가 401 로 통일 판단한다.
 */
public record LoginRequest(
        @NotBlank String email,
        @NotBlank String password
) {

    public LoginCommand toCommand() {
        return new LoginCommand(email, password);
    }
}
