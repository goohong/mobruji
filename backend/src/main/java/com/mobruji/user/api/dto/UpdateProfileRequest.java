package com.mobruji.user.api.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import com.mobruji.user.application.UpdateProfileCommand;
import com.mobruji.user.domain.UserGender;
import com.mobruji.voice.domain.MidiRange;

/**
 * {@code PATCH /api/v1/users/me} 요청 body. 모든 필드 선택 — 음역대는 동시 입력/동시 미입력 + low<=high.
 */
public record UpdateProfileRequest(
        UserGender gender,
        @Min(MidiRange.LOWEST_ALLOWED_MIDI) @Max(MidiRange.HIGHEST_ALLOWED_MIDI) Integer vocalRangeLowMidi,
        @Min(MidiRange.LOWEST_ALLOWED_MIDI) @Max(MidiRange.HIGHEST_ALLOWED_MIDI) Integer vocalRangeHighMidi
) {

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

    public UpdateProfileCommand toCommand() {
        return new UpdateProfileCommand(gender, vocalRangeLowMidi, vocalRangeHighMidi);
    }
}
