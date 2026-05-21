package com.mobruji.voice.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mobruji.voice.api.dto.VoiceRangeCreateRequest;
import com.mobruji.voice.api.dto.VoiceRangeResponse;
import com.mobruji.voice.api.dto.VoiceRangeUpdateRequest;

import lombok.RequiredArgsConstructor;

import com.mobruji.voice.domain.VoiceRange;
import com.mobruji.voice.domain.VoiceRangeNotFoundException;
import com.mobruji.voice.infrastructure.VoiceRangeRepository;

@Service
@Transactional
@RequiredArgsConstructor
public class VoiceRangeService {

    private final VoiceRangeRepository voiceRangeRepository;

    public VoiceRangeResponse createOrReplace(final VoiceRangeCreateRequest voiceRangeCreateRequest) {
        final VoiceRange voiceRange = voiceRangeRepository
                .findBySessionId(voiceRangeCreateRequest.sessionId())
                .map(existing -> {
                    existing.updateRange(
                            voiceRangeCreateRequest.lowestNoteMidi(),
                            voiceRangeCreateRequest.highestNoteMidi(),
                            voiceRangeCreateRequest.sourceMethod());
                    return existing;
                })
                .orElseGet(() -> voiceRangeRepository.save(VoiceRange.create(
                        voiceRangeCreateRequest.sessionId(),
                        voiceRangeCreateRequest.lowestNoteMidi(),
                        voiceRangeCreateRequest.highestNoteMidi(),
                        voiceRangeCreateRequest.sourceMethod())));
        return VoiceRangeResponse.from(voiceRange);
    }

    @Transactional(readOnly = true)
    public VoiceRangeResponse readBySessionId(final String sessionId) {
        return voiceRangeRepository.findBySessionId(sessionId)
                .map(VoiceRangeResponse::from)
                .orElseThrow(() -> new VoiceRangeNotFoundException(sessionId));
    }

    public VoiceRangeResponse updateBySessionId(
            final String sessionId,
            final VoiceRangeUpdateRequest voiceRangeUpdateRequest) {
        final VoiceRange voiceRange = voiceRangeRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new VoiceRangeNotFoundException(sessionId));
        voiceRange.updateRange(
                voiceRangeUpdateRequest.lowestNoteMidi(),
                voiceRangeUpdateRequest.highestNoteMidi(),
                voiceRangeUpdateRequest.sourceMethod());
        return VoiceRangeResponse.from(voiceRange);
    }
}
