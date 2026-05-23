package com.mobruji.voice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.voice.domain.VoiceRangeSourceMethod;

/**
 * {@link UpdateVoiceRangeCommand} record 회귀 가드.
 *
 * <p>Create와 달리 sessionId 필드가 없으며 sourceMethod null 가드만 남아 있다 — 가드가 사라지면 도메인 단까지 NPE가
 * 전파된다.
 */
class UpdateVoiceRangeCommandTest {

    @Test
    @DisplayName("유효한 입력이면 정상 생성")
    void create_withValidInput_setsAllFields() {
        final UpdateVoiceRangeCommand updateVoiceRangeCommand = new UpdateVoiceRangeCommand(
                50, 72, VoiceRangeSourceMethod.MIC_MEASURE);

        assertThat(updateVoiceRangeCommand.lowestNoteMidi()).isEqualTo(50);
        assertThat(updateVoiceRangeCommand.highestNoteMidi()).isEqualTo(72);
        assertThat(updateVoiceRangeCommand.sourceMethod()).isEqualTo(VoiceRangeSourceMethod.MIC_MEASURE);
    }

    @Test
    @DisplayName("sourceMethod가 null이면 NullPointerException")
    void create_withNullSourceMethod_throws() {
        assertThatThrownBy(() -> new UpdateVoiceRangeCommand(50, 72, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sourceMethod");
    }
}
