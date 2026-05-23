package com.mobruji.voice.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link VoiceRangeSourceMethod} enum 회귀 가드.
 *
 * <p>DB(`source_method` 컬럼)에 enum name이 그대로 저장되고, fe와도 문자열로 교환되므로 value 추가/이름 변경은 호환성을 깨뜨린다.
 * spec: docs/features/voice-range-input.md Q1 — PoC는 OCTAVE_PICK 위주.
 */
class VoiceRangeSourceMethodTest {

    @Test
    @DisplayName("세 개의 source method가 정의되어 있다")
    void values_containsExactlyThreeMethods() {
        assertThat(VoiceRangeSourceMethod.values())
                .hasSize(3)
                .containsExactlyInAnyOrder(
                        VoiceRangeSourceMethod.SELF_REPORT,
                        VoiceRangeSourceMethod.OCTAVE_PICK,
                        VoiceRangeSourceMethod.MIC_MEASURE);
    }

    @Test
    @DisplayName("valueOf로 SELF_REPORT 복원 가능")
    void valueOf_selfReport_roundTrips() {
        assertThat(VoiceRangeSourceMethod.valueOf("SELF_REPORT")).isEqualTo(VoiceRangeSourceMethod.SELF_REPORT);
    }

    @Test
    @DisplayName("valueOf로 OCTAVE_PICK 복원 가능")
    void valueOf_octavePick_roundTrips() {
        assertThat(VoiceRangeSourceMethod.valueOf("OCTAVE_PICK")).isEqualTo(VoiceRangeSourceMethod.OCTAVE_PICK);
    }

    @Test
    @DisplayName("valueOf로 MIC_MEASURE 복원 가능")
    void valueOf_micMeasure_roundTrips() {
        assertThat(VoiceRangeSourceMethod.valueOf("MIC_MEASURE")).isEqualTo(VoiceRangeSourceMethod.MIC_MEASURE);
    }
}
