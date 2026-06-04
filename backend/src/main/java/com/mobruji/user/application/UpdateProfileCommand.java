package com.mobruji.user.application;

import com.mobruji.user.domain.UserGender;

/**
 * 음역대/성별 프로필 갱신 입력. 모든 필드 선택 — 음역대는 동시 null 또는 동시 present (검증은
 * {@code User} 도메인).
 */
public record UpdateProfileCommand(
        UserGender gender,
        Integer vocalRangeLowMidi,
        Integer vocalRangeHighMidi
) {
}
