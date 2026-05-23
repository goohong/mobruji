package com.mobruji.voice.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import com.mobruji.voice.application.UpdateVoiceRangeCommand;
import com.mobruji.voice.domain.MidiRange;
import com.mobruji.voice.domain.VoiceRangeSourceMethod;

public record VoiceRangeUpdateRequest(
        @NotNull @Min(MidiRange.LOWEST_ALLOWED_MIDI) @Max(MidiRange.HIGHEST_ALLOWED_MIDI) Integer lowestNoteMidi,
        @NotNull @Min(MidiRange.LOWEST_ALLOWED_MIDI) @Max(MidiRange.HIGHEST_ALLOWED_MIDI) Integer highestNoteMidi,
        @NotNull VoiceRangeSourceMethod sourceMethod
) {

    public UpdateVoiceRangeCommand toCommand() {
        return new UpdateVoiceRangeCommand(lowestNoteMidi, highestNoteMidi, sourceMethod);
    }
}
