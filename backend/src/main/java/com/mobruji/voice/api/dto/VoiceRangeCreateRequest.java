package com.mobruji.voice.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.mobruji.voice.application.CreateVoiceRangeCommand;
import com.mobruji.voice.domain.MidiRange;
import com.mobruji.voice.domain.VoiceRangeSourceMethod;

public record VoiceRangeCreateRequest(
        @NotBlank String sessionId,
        @NotNull @Min(MidiRange.LOWEST_ALLOWED_MIDI) @Max(MidiRange.HIGHEST_ALLOWED_MIDI) Integer lowestNoteMidi,
        @NotNull @Min(MidiRange.LOWEST_ALLOWED_MIDI) @Max(MidiRange.HIGHEST_ALLOWED_MIDI) Integer highestNoteMidi,
        @NotNull VoiceRangeSourceMethod sourceMethod
) {

    public CreateVoiceRangeCommand toCommand() {
        return new CreateVoiceRangeCommand(sessionId, lowestNoteMidi, highestNoteMidi, sourceMethod);
    }
}
