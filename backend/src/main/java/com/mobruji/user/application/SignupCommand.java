package com.mobruji.user.application;

import java.util.Objects;

import com.mobruji.user.domain.UserGender;

/**
 * 이메일 회원가입 입력. 컨트롤러 DTO 와 분리된 application 입력 (ADR 0005 §A-7).
 *
 * <p>{@code vocalRangeLowMidi}/{@code vocalRangeHighMidi} 는 선택 — 동시 null 또는 동시 present
 * (검증은 {@code User} 도메인이 수행).
 */
public record SignupCommand(
        String email,
        String rawPassword,
        UserGender gender,
        Integer vocalRangeLowMidi,
        Integer vocalRangeHighMidi
) {

    public SignupCommand {
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(rawPassword, "rawPassword must not be null");
    }
}
