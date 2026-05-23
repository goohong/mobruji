package com.mobruji.voice.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VoiceRangeSnapshotTest {

    @Test
    @DisplayName("create: 유효한 입력이면 정상 생성, measuredAt 자동 채움")
    void create_withValidInput_setsMeasuredAt() {
        // given
        final String sessionId = "snap-session-1";
        final int lowMidi = 48;
        final int highMidi = 69;
        final VoiceRangeSourceMethod sourceMethod = VoiceRangeSourceMethod.OCTAVE_PICK;

        // when
        final VoiceRangeSnapshot voiceRangeSnapshot = VoiceRangeSnapshot.create(
                sessionId, lowMidi, highMidi, sourceMethod);

        // then
        assertThat(voiceRangeSnapshot.getSessionId()).isEqualTo(sessionId);
        assertThat(voiceRangeSnapshot.getLowMidi()).isEqualTo(lowMidi);
        assertThat(voiceRangeSnapshot.getHighMidi()).isEqualTo(highMidi);
        assertThat(voiceRangeSnapshot.getSourceMethod()).isEqualTo(sourceMethod);
        assertThat(voiceRangeSnapshot.getMeasuredAt()).isNotNull();
        assertThat(voiceRangeSnapshot.getId()).isNull();
    }

    @Test
    @DisplayName("create: 저음이 고음보다 크면 IllegalArgumentException")
    void create_withLowGreaterThanHigh_throws() {
        assertThatThrownBy(() -> VoiceRangeSnapshot.create("s", 70, 60, VoiceRangeSourceMethod.OCTAVE_PICK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be <=");
    }

    @Test
    @DisplayName("create: lowMidi가 허용 범위 미만이면 IllegalArgumentException")
    void create_withLowBelowAllowed_throws() {
        assertThatThrownBy(() -> VoiceRangeSnapshot.create("s", 5, 60, VoiceRangeSourceMethod.OCTAVE_PICK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lowMidi out of allowed range");
    }

    @Test
    @DisplayName("create: highMidi가 허용 범위 초과면 IllegalArgumentException")
    void create_withHighAboveAllowed_throws() {
        assertThatThrownBy(() -> VoiceRangeSnapshot.create("s", 48, 200, VoiceRangeSourceMethod.OCTAVE_PICK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("highMidi out of allowed range");
    }

    @Test
    @DisplayName("create: sessionId가 null이면 NullPointerException")
    void create_withNullSessionId_throws() {
        assertThatThrownBy(() -> VoiceRangeSnapshot.create(null, 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sessionId");
    }

    @Test
    @DisplayName("create: sourceMethod가 null이면 NullPointerException")
    void create_withNullSourceMethod_throws() {
        assertThatThrownBy(() -> VoiceRangeSnapshot.create("s", 48, 69, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sourceMethod");
    }

    @Test
    @DisplayName("fromVoiceRange: VoiceRange의 필드를 그대로 복사한 스냅샷을 생성")
    void fromVoiceRange_copiesFields() {
        // given
        final VoiceRange voiceRange = VoiceRange.create("vr-s", 50, 72, VoiceRangeSourceMethod.MIC_MEASURE);

        // when
        final VoiceRangeSnapshot voiceRangeSnapshot = VoiceRangeSnapshot.fromVoiceRange(voiceRange);

        // then
        assertThat(voiceRangeSnapshot.getSessionId()).isEqualTo("vr-s");
        assertThat(voiceRangeSnapshot.getLowMidi()).isEqualTo(50);
        assertThat(voiceRangeSnapshot.getHighMidi()).isEqualTo(72);
        assertThat(voiceRangeSnapshot.getSourceMethod()).isEqualTo(VoiceRangeSourceMethod.MIC_MEASURE);
        assertThat(voiceRangeSnapshot.getMeasuredAt()).isNotNull();
    }

    @Test
    @DisplayName("fromVoiceRange: null이면 NullPointerException")
    void fromVoiceRange_withNull_throws() {
        assertThatThrownBy(() -> VoiceRangeSnapshot.fromVoiceRange(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("voiceRange");
    }

    @Test
    @DisplayName("create: 경계값 12/119 (C0~B8 닫힌 구간) 이면 정상 생성")
    void create_atBoundary12And119_succeeds() {
        final VoiceRangeSnapshot voiceRangeSnapshot = VoiceRangeSnapshot.create(
                "s-b", 12, 119, VoiceRangeSourceMethod.OCTAVE_PICK);
        assertThat(voiceRangeSnapshot.getLowMidi()).isEqualTo(12);
        assertThat(voiceRangeSnapshot.getHighMidi()).isEqualTo(119);
    }

    @Test
    @DisplayName("create: low == high (1음만 측정) 이어도 정상 생성")
    void create_withEqualLowAndHigh_succeeds() {
        final VoiceRangeSnapshot voiceRangeSnapshot = VoiceRangeSnapshot.create(
                "s-eq", 60, 60, VoiceRangeSourceMethod.OCTAVE_PICK);
        assertThat(voiceRangeSnapshot.getLowMidi()).isEqualTo(voiceRangeSnapshot.getHighMidi());
    }
}
