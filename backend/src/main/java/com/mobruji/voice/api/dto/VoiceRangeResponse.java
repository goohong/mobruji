package com.mobruji.voice.api.dto;

import java.time.LocalDateTime;

import com.mobruji.voice.domain.VoiceRange;
import com.mobruji.voice.domain.VoiceRangeSourceMethod;

public record VoiceRangeResponse(
        Long id,
        String sessionId,
        int lowestNoteMidi,
        int highestNoteMidi,
        VoiceRangeSourceMethod sourceMethod,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static VoiceRangeResponse from(final VoiceRange voiceRange) {
        return new VoiceRangeResponse(
                voiceRange.getId(),
                voiceRange.getSessionId(),
                voiceRange.getLowestNoteMidi(),
                voiceRange.getHighestNoteMidi(),
                voiceRange.getSourceMethod(),
                voiceRange.getCreatedAt(),
                voiceRange.getUpdatedAt());
    }
}
