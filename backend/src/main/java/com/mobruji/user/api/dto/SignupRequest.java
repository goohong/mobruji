package com.mobruji.user.api.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.mobruji.user.application.SignupCommand;
import com.mobruji.user.domain.User;
import com.mobruji.user.domain.UserGender;
import com.mobruji.voice.domain.MidiRange;

/**
 * {@code POST /api/v1/users/signup} 요청 body.
 *
 * <p>spec: 인증·음역대 프로필 영속 (#1491).
 *
 * <ul>
 * <li>{@code email}: 로그인 식별자. RFC 형식 + 최대 254자.</li>
 * <li>{@code password}: 8~128자. 평문은 응답/로그에 노출하지 않는다.</li>
 * <li>{@code gender}/{@code vocalRangeLowMidi}/{@code vocalRangeHighMidi}: 선택 프로필. 음역대는
 * 동시 입력 또는 동시 미입력 + low<=high — DTO 경계에서 400 으로 거부 (도메인은 불변식 가드).</li>
 * </ul>
 */
public record SignupRequest(
        @NotBlank @Email @Size(max = User.EMAIL_MAX_LENGTH) String email,
        @NotBlank @Size(min = PASSWORD_MIN_LENGTH, max = PASSWORD_MAX_LENGTH) String password,
        UserGender gender,
        @Min(MidiRange.LOWEST_ALLOWED_MIDI) @Max(MidiRange.HIGHEST_ALLOWED_MIDI) Integer vocalRangeLowMidi,
        @Min(MidiRange.LOWEST_ALLOWED_MIDI) @Max(MidiRange.HIGHEST_ALLOWED_MIDI) Integer vocalRangeHighMidi
) {

    public static final int PASSWORD_MIN_LENGTH = 8;

    public static final int PASSWORD_MAX_LENGTH = 128;

    @AssertTrue(message = "vocalRangeLowMidi 와 vocalRangeHighMidi 는 함께 입력하고 low<=high 여야 합니다")
    public boolean isVocalRangeConsistent() {
        if (vocalRangeLowMidi == null && vocalRangeHighMidi == null) {
            return true;
        }
        if (vocalRangeLowMidi == null || vocalRangeHighMidi == null) {
            return false;
        }
        return vocalRangeLowMidi <= vocalRangeHighMidi;
    }

    public SignupCommand toCommand() {
        return new SignupCommand(email, password, gender, vocalRangeLowMidi, vocalRangeHighMidi);
    }
}
