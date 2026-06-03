package com.mobruji.user.api;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.mobruji.user.api.dto.UpdateProfileRequest;
import com.mobruji.user.api.dto.UserProfileResponse;
import com.mobruji.user.application.UserAccountService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 인증된 사용자의 음역대/성별 프로필 조회·갱신 endpoint.
 *
 * <p>spec: 인증·음역대 프로필 영속 (#1491). 인증은 {@code Authorization: Bearer <token>} 헤더 —
 * {@link UserAccountService#authenticate(String)} 가 누락/무효 시 401 로 차단한다.
 *
 * <ul>
 * <li>{@code GET /api/v1/users/me} — 프로필 조회.</li>
 * <li>{@code PATCH /api/v1/users/me} — 음역대/성별 갱신.</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class UserProfileController {

    private final UserAccountService userAccountService;

    @GetMapping("/api/v1/users/me")
    public UserProfileResponse me(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) final String authorization) {
        final Long userId = userAccountService.authenticate(authorization);
        return UserProfileResponse.from(userAccountService.getProfile(userId));
    }

    @PatchMapping("/api/v1/users/me")
    public UserProfileResponse updateMe(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) final String authorization,
            @Valid @RequestBody final UpdateProfileRequest updateProfileRequest) {
        final Long userId = userAccountService.authenticate(authorization);
        return UserProfileResponse.from(userAccountService.updateProfile(userId, updateProfileRequest.toCommand()));
    }
}
