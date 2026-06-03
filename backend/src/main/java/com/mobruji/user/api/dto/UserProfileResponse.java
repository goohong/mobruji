package com.mobruji.user.api.dto;

import com.mobruji.user.domain.AuthProvider;
import com.mobruji.user.domain.User;
import com.mobruji.user.domain.UserGender;

/**
 * {@code GET/PATCH /api/v1/users/me} 응답 — 영속된 음역대/성별 프로필. 재방문 시 이 값으로 재입력을
 * 생략한다.
 */
public record UserProfileResponse(
        Long userId,
        String email,
        AuthProvider authProvider,
        UserGender gender,
        Integer vocalRangeLowMidi,
        Integer vocalRangeHighMidi
) {

    public static UserProfileResponse from(final User user) {
        return new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getAuthProvider(),
                user.getGender(),
                user.getVocalRangeLowMidi(),
                user.getVocalRangeHighMidi());
    }
}
