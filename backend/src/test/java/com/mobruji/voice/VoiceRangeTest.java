package com.mobruji.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VoiceRangeTest {

    @Test
    @DisplayName("유효한 음역으로 create하면 정상 생성된다")
    void create_withValidRange_createsInstance() {
        // given
        final String sessionId = "test-session-1";
        final int lowestNoteMidi = 48; // C3
        final int highestNoteMidi = 69; // A4
        final VoiceRangeSourceMethod sourceMethod = VoiceRangeSourceMethod.OCTAVE_PICK;

        // when
        final VoiceRange voiceRange = VoiceRange.create(sessionId, lowestNoteMidi, highestNoteMidi, sourceMethod);

        // then
        assertThat(voiceRange.getSessionId()).isEqualTo(sessionId);
        assertThat(voiceRange.getLowestNoteMidi()).isEqualTo(lowestNoteMidi);
        assertThat(voiceRange.getHighestNoteMidi()).isEqualTo(highestNoteMidi);
        assertThat(voiceRange.getSourceMethod()).isEqualTo(sourceMethod);
        assertThat(voiceRange.getCreatedAt()).isNotNull();
        assertThat(voiceRange.getUpdatedAt()).isEqualTo(voiceRange.getCreatedAt());
    }

    @Test
    @DisplayName("저음이 고음보다 크면 IllegalArgumentException")
    void create_withLowestGreaterThanHighest_throws() {
        assertThatThrownBy(() -> VoiceRange.create("s", 70, 60, VoiceRangeSourceMethod.OCTAVE_PICK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be <=");
    }

    @Test
    @DisplayName("MIDI 범위(12~119) 밖이면 IllegalArgumentException")
    void create_withOutOfRangeMidi_throws() {
        assertThatThrownBy(() -> VoiceRange.create("s", 5, 60, VoiceRangeSourceMethod.OCTAVE_PICK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lowestNoteMidi out of allowed range");

        assertThatThrownBy(() -> VoiceRange.create("s", 60, 200, VoiceRangeSourceMethod.OCTAVE_PICK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("highestNoteMidi out of allowed range");
    }

    @Test
    @DisplayName("sessionId null이면 NullPointerException")
    void create_withNullSessionId_throws() {
        assertThatThrownBy(() -> VoiceRange.create(null, 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sessionId");
    }

    @Test
    @DisplayName("sourceMethod null이면 NullPointerException")
    void create_withNullSourceMethod_throws() {
        assertThatThrownBy(() -> VoiceRange.create("s", 48, 69, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sourceMethod");
    }

    @Test
    @DisplayName("updateRange 호출하면 필드가 갱신되고 updatedAt이 변한다")
    void updateRange_called_updatesFieldsAndTimestamp() throws InterruptedException {
        // given
        final VoiceRange voiceRange = VoiceRange.create("s", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK);
        final java.time.LocalDateTime initialUpdatedAt = voiceRange.getUpdatedAt();
        Thread.sleep(5); // ensure measurable timestamp diff

        // when
        voiceRange.updateRange(50, 72, VoiceRangeSourceMethod.MIC_MEASURE);

        // then
        assertThat(voiceRange.getLowestNoteMidi()).isEqualTo(50);
        assertThat(voiceRange.getHighestNoteMidi()).isEqualTo(72);
        assertThat(voiceRange.getSourceMethod()).isEqualTo(VoiceRangeSourceMethod.MIC_MEASURE);
        assertThat(voiceRange.getUpdatedAt()).isAfter(initialUpdatedAt);
    }

    @Test
    @DisplayName("updateRange에 null sourceMethod 주면 NullPointerException")
    void updateRange_withNullSourceMethod_throws() {
        final VoiceRange voiceRange = VoiceRange.create("s", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK);
        assertThatThrownBy(() -> voiceRange.updateRange(50, 72, null))
                .isInstanceOf(NullPointerException.class);
    }
}
