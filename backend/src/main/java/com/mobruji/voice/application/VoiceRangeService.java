package com.mobruji.voice.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.mobruji.voice.domain.VoiceRange;
import com.mobruji.voice.domain.VoiceRangeNotFoundException;
import com.mobruji.voice.infrastructure.VoiceRangeRepository;

@Service
@Transactional
@RequiredArgsConstructor
public class VoiceRangeService {

    private final VoiceRangeRepository voiceRangeRepository;

    public VoiceRange createOrReplace(final CreateVoiceRangeCommand createVoiceRangeCommand) {
        return voiceRangeRepository
                .findBySessionId(createVoiceRangeCommand.sessionId())
                .map(existing -> {
                    existing.updateRange(
                            createVoiceRangeCommand.lowestNoteMidi(),
                            createVoiceRangeCommand.highestNoteMidi(),
                            createVoiceRangeCommand.sourceMethod());
                    return existing;
                })
                .orElseGet(() -> voiceRangeRepository.save(VoiceRange.create(
                        createVoiceRangeCommand.sessionId(),
                        createVoiceRangeCommand.lowestNoteMidi(),
                        createVoiceRangeCommand.highestNoteMidi(),
                        createVoiceRangeCommand.sourceMethod())));
    }

    @Transactional(readOnly = true)
    public VoiceRange readBySessionId(final String sessionId) {
        return voiceRangeRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new VoiceRangeNotFoundException(sessionId));
    }

    public VoiceRange updateBySessionId(
            final String sessionId,
            final UpdateVoiceRangeCommand updateVoiceRangeCommand) {
        final VoiceRange voiceRange = voiceRangeRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new VoiceRangeNotFoundException(sessionId));
        voiceRange.updateRange(
                updateVoiceRangeCommand.lowestNoteMidi(),
                updateVoiceRangeCommand.highestNoteMidi(),
                updateVoiceRangeCommand.sourceMethod());
        return voiceRange;
    }
}
