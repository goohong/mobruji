package com.mobruji.voice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.voice.domain.VoiceRangeSourceMethod;

/**
 * {@link CreateVoiceRangeCommand} record 회귀 가드.
 *
 * <p>application 계층은 api.dto의 Bean Validation을 거치지 않고 호출될 수 있으므로 compact constructor의 null 가드가
 * 마지막 방어선이다. 이 가드가 빠지면 service/도메인에서 NPE가 뒤늦게 터진다.
 */
class CreateVoiceRangeCommandTest {

    @Test
    @DisplayName("유효한 입력이면 정상 생성")
    void create_withValidInput_setsAllFields() {
        final CreateVoiceRangeCommand createVoiceRangeCommand = new CreateVoiceRangeCommand(
                "s-1", 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK);

        assertThat(createVoiceRangeCommand.sessionId()).isEqualTo("s-1");
        assertThat(createVoiceRangeCommand.lowestNoteMidi()).isEqualTo(48);
        assertThat(createVoiceRangeCommand.highestNoteMidi()).isEqualTo(69);
        assertThat(createVoiceRangeCommand.sourceMethod()).isEqualTo(VoiceRangeSourceMethod.OCTAVE_PICK);
    }

    @Test
    @DisplayName("sessionId가 null이면 NullPointerException")
    void create_withNullSessionId_throws() {
        assertThatThrownBy(() -> new CreateVoiceRangeCommand(null, 48, 69, VoiceRangeSourceMethod.OCTAVE_PICK))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sessionId");
    }

    @Test
    @DisplayName("sourceMethod가 null이면 NullPointerException")
    void create_withNullSourceMethod_throws() {
        assertThatThrownBy(() -> new CreateVoiceRangeCommand("s", 48, 69, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sourceMethod");
    }
}
