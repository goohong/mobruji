package com.mobruji.voice.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.mobruji.voice.domain.VoiceRangeSourceMethod;

public record VoiceRangeCreateRequest(
        @NotBlank String sessionId,
        @NotNull @Min(12) @Max(119) Integer lowestNoteMidi,
        @NotNull @Min(12) @Max(119) Integer highestNoteMidi,
        @NotNull VoiceRangeSourceMethod sourceMethod
) {
}
