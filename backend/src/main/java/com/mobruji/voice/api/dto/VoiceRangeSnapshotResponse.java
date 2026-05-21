package com.mobruji.voice.api.dto;

import java.time.LocalDateTime;

import com.mobruji.song.domain.NoteName;
import com.mobruji.voice.domain.VoiceRangeSnapshot;
import com.mobruji.voice.domain.VoiceRangeSourceMethod;

/**
 * 음역 측정 시계열 스냅샷 1건의 응답 DTO.
 *
 * <p>spec: docs/features/voice-range-progress.md §5-2.
 * `lowestNoteName`/`highestNoteName`은 MIDI 값을 {@link NoteName} 컨벤션으로 변환한 표기로, fe history 페이지에서 별도 변환 없이 표시하기 위한 보조 필드다.
 */
public record VoiceRangeSnapshotResponse(
        Long id,
        int lowMidi,
        int highMidi,
        String lowestNoteName,
        String highestNoteName,
        VoiceRangeSourceMethod sourceMethod,
        LocalDateTime measuredAt
) {

    public static VoiceRangeSnapshotResponse from(final VoiceRangeSnapshot voiceRangeSnapshot) {
        return new VoiceRangeSnapshotResponse(
                voiceRangeSnapshot.getId(),
                voiceRangeSnapshot.getLowMidi(),
                voiceRangeSnapshot.getHighMidi(),
                NoteName.of(voiceRangeSnapshot.getLowMidi()),
                NoteName.of(voiceRangeSnapshot.getHighMidi()),
                voiceRangeSnapshot.getSourceMethod(),
                voiceRangeSnapshot.getMeasuredAt());
    }
}
