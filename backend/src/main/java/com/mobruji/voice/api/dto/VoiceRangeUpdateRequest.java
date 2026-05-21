package com.mobruji.voice.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import com.mobruji.voice.application.UpdateVoiceRangeCommand;
import com.mobruji.voice.domain.VoiceRangeSourceMethod;

public record VoiceRangeUpdateRequest(
        @NotNull @Min(12) @Max(119) Integer lowestNoteMidi,
        @NotNull @Min(12) @Max(119) Integer highestNoteMidi,
        @NotNull VoiceRangeSourceMethod sourceMethod
) {

    public UpdateVoiceRangeCommand toCommand() {
        return new UpdateVoiceRangeCommand(lowestNoteMidi, highestNoteMidi, sourceMethod);
    }
}
