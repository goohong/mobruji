package com.mobruji.user.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mobruji.user.api.dto.AuthResponse;
import com.mobruji.user.api.dto.LoginRequest;
import com.mobruji.user.api.dto.SignupRequest;
import com.mobruji.user.application.UserAccountService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 이메일 회원가입/로그인 endpoint.
 *
 * <p>spec: 인증·음역대 프로필 영속 (#1491).
 *
 * <ul>
 * <li>{@code POST /api/v1/users/signup} — 가입 + 토큰 발급 (201). 이메일 중복 → 409.</li>
 * <li>{@code POST /api/v1/users/login} — 자격 검증 + 토큰 발급 (200). 불일치 → 401.</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class UserAuthController {

    private final UserAccountService userAccountService;

    @PostMapping("/api/v1/users/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse signup(@Valid @RequestBody final SignupRequest signupRequest) {
        return AuthResponse.from(userAccountService.signup(signupRequest.toCommand()));
    }

    @PostMapping("/api/v1/users/login")
    public AuthResponse login(@Valid @RequestBody final LoginRequest loginRequest) {
        return AuthResponse.from(userAccountService.login(loginRequest.toCommand()));
    }
}
