package com.mobruji.voice.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.voice.domain.VoiceRangeSnapshot;
import com.mobruji.voice.domain.VoiceRangeSourceMethod;

/**
 * {@link VoiceRangeSnapshotResponse#from} 매핑 회귀 가드.
 *
 * <p>spec: docs/features/voice-range-progress.md §5-2. fe 표기 컨벤션과 일치해야 하므로 NoteName 변환 결과를 직접
 * 검증한다 (60→"C4", 12→"C0", 119→"B8").
 */
class VoiceRangeSnapshotResponseTest {

    @Test
    @DisplayName("from: 도메인 필드 + NoteName 변환을 응답 DTO에 매핑")
    void from_mapsFieldsAndNoteName() {
        // given
        final VoiceRangeSnapshot voiceRangeSnapshot = VoiceRangeSnapshot.create(
                "s-1", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK);

        // when
        final VoiceRangeSnapshotResponse voiceRangeSnapshotResponse = VoiceRangeSnapshotResponse.from(
                voiceRangeSnapshot);

        // then
        assertThat(voiceRangeSnapshotResponse.lowMidi()).isEqualTo(48);
        assertThat(voiceRangeSnapshotResponse.highMidi()).isEqualTo(69);
        assertThat(voiceRangeSnapshotResponse.lowestNoteName()).isEqualTo("C3");
        assertThat(voiceRangeSnapshotResponse.highestNoteName()).isEqualTo("A4");
        assertThat(voiceRangeSnapshotResponse.sourceMethod()).isEqualTo(VoiceRangeSourceMethod.OCTAVE_PICK);
        assertThat(voiceRangeSnapshotResponse.measuredAt()).isNotNull();
    }

    @Test
    @DisplayName("from: 경계값 12/119 — NoteName 변환이 C0/B8")
    void from_boundaryMidi_mapsToC0AndB8() {
        // given
        final VoiceRangeSnapshot voiceRangeSnapshot = VoiceRangeSnapshot.create(
                "s-b", 12, 119, VoiceRangeSourceMethod.SELF_REPORT);

        // when
        final VoiceRangeSnapshotResponse voiceRangeSnapshotResponse = VoiceRangeSnapshotResponse.from(
                voiceRangeSnapshot);

        // then
        assertThat(voiceRangeSnapshotResponse.lowestNoteName()).isEqualTo("C0");
        assertThat(voiceRangeSnapshotResponse.highestNoteName()).isEqualTo("B8");
    }

    @Test
    @DisplayName("from: 샵 표기 — MIDI 61/73 → C#4/C#5")
    void from_sharpPitches_useAsciiHash() {
        // given
        final VoiceRangeSnapshot voiceRangeSnapshot = VoiceRangeSnapshot.create(
                "s-sharp", 61, 73, VoiceRangeSourceMethod.MIC_MEASURE);

        // when
        final VoiceRangeSnapshotResponse voiceRangeSnapshotResponse = VoiceRangeSnapshotResponse.from(
                voiceRangeSnapshot);

        // then
        assertThat(voiceRangeSnapshotResponse.lowestNoteName()).isEqualTo("C#4");
        assertThat(voiceRangeSnapshotResponse.highestNoteName()).isEqualTo("C#5");
    }
}
