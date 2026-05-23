package com.mobruji.voice.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.mobruji.voice.domain.VoiceRange;
import com.mobruji.voice.domain.VoiceRangeNotFoundException;
import com.mobruji.voice.domain.VoiceRangeSnapshot;
import com.mobruji.voice.infrastructure.VoiceRangeRepository;
import com.mobruji.voice.infrastructure.VoiceRangeSnapshotRepository;

@Service
@Transactional
@RequiredArgsConstructor
public class VoiceRangeService {

    private final VoiceRangeRepository voiceRangeRepository;
    private final VoiceRangeSnapshotRepository voiceRangeSnapshotRepository;

    public VoiceRange createOrReplace(final CreateVoiceRangeCommand createVoiceRangeCommand) {
        final VoiceRange voiceRange = voiceRangeRepository
                .findBySessionId(createVoiceRangeCommand.sessionId())
                .map(existingVoiceRange -> {
                    existingVoiceRange.updateRange(
                            createVoiceRangeCommand.lowestNoteMidi(),
                            createVoiceRangeCommand.highestNoteMidi(),
                            createVoiceRangeCommand.sourceMethod());
                    return existingVoiceRange;
                })
                .orElseGet(() -> voiceRangeRepository.save(VoiceRange.create(
                        createVoiceRangeCommand.sessionId(),
                        createVoiceRangeCommand.lowestNoteMidi(),
                        createVoiceRangeCommand.highestNoteMidi(),
                        createVoiceRangeCommand.sourceMethod())));
        voiceRangeSnapshotRepository.save(VoiceRangeSnapshot.fromVoiceRange(voiceRange));
        return voiceRange;
    }

    @Transactional(readOnly = true)
    public VoiceRange readBySessionId(final String sessionId) {
        return voiceRangeRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new VoiceRangeNotFoundException(sessionId));
    }

    @Transactional(readOnly = true)
    public List<VoiceRangeSnapshot> readHistoryBySessionId(final String sessionId) {
        return voiceRangeSnapshotRepository.findBySessionIdOrderByMeasuredAtAsc(sessionId);
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
        voiceRangeSnapshotRepository.save(VoiceRangeSnapshot.fromVoiceRange(voiceRange));
        return voiceRange;
    }
}
