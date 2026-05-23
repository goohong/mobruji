package com.mobruji.voice.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.voice.application.CreateVoiceRangeCommand;
import com.mobruji.voice.domain.MidiRange;
import com.mobruji.voice.domain.VoiceRangeSourceMethod;

/**
 * VoiceRangeCreateRequest 메타데이터 회귀 가드.
 *
 * <p>sessionId 의 @Size(max=64) 는 DB column length 와 정합되어야 한다 (V1/V5 migration).
 */
class VoiceRangeCreateRequestTest {

    @Test
    @DisplayName("sessionId: @NotBlank + @Size(max=64) 메타데이터 존재")
    void sessionIdValidationMetadata_present() throws NoSuchMethodException {
        assertThat(VoiceRangeCreateRequest.class.getDeclaredMethod("sessionId").getAnnotation(NotBlank.class))
                .isNotNull();

        final Size sessionIdSize = VoiceRangeCreateRequest.class
                .getDeclaredMethod("sessionId").getAnnotation(Size.class);
        assertThat(sessionIdSize).isNotNull();
        assertThat(sessionIdSize.max()).isEqualTo(64);
    }

    @Test
    @DisplayName("lowestNoteMidi/highestNoteMidi: @NotNull + @Min(LOWEST) + @Max(HIGHEST) (MidiRange 상수와 일치)")
    void midiRangeValidationMetadata_alignsWithDomainConstants() throws NoSuchMethodException {
        final Min lowestMin = VoiceRangeCreateRequest.class
                .getDeclaredMethod("lowestNoteMidi").getAnnotation(Min.class);
        final Max lowestMax = VoiceRangeCreateRequest.class
                .getDeclaredMethod("lowestNoteMidi").getAnnotation(Max.class);
        final NotNull lowestNotNull = VoiceRangeCreateRequest.class
                .getDeclaredMethod("lowestNoteMidi").getAnnotation(NotNull.class);

        assertThat(lowestNotNull).isNotNull();
        assertThat(lowestMin.value()).isEqualTo(MidiRange.LOWEST_ALLOWED_MIDI);
        assertThat(lowestMax.value()).isEqualTo(MidiRange.HIGHEST_ALLOWED_MIDI);

        final Min highestMin = VoiceRangeCreateRequest.class
                .getDeclaredMethod("highestNoteMidi").getAnnotation(Min.class);
        final Max highestMax = VoiceRangeCreateRequest.class
                .getDeclaredMethod("highestNoteMidi").getAnnotation(Max.class);
        final NotNull highestNotNull = VoiceRangeCreateRequest.class
                .getDeclaredMethod("highestNoteMidi").getAnnotation(NotNull.class);

        assertThat(highestNotNull).isNotNull();
        assertThat(highestMin.value()).isEqualTo(MidiRange.LOWEST_ALLOWED_MIDI);
        assertThat(highestMax.value()).isEqualTo(MidiRange.HIGHEST_ALLOWED_MIDI);
    }

    @Test
    @DisplayName("sourceMethod: @NotNull 메타데이터 존재")
    void sourceMethodValidationMetadata_present() throws NoSuchMethodException {
        assertThat(VoiceRangeCreateRequest.class.getDeclaredMethod("sourceMethod").getAnnotation(NotNull.class))
                .isNotNull();
    }

    @Test
    @DisplayName("toCommand: 모든 필드를 CreateVoiceRangeCommand 로 그대로 매핑")
    void toCommand_mapsAllFields() {
        final VoiceRangeCreateRequest voiceRangeCreateRequest = new VoiceRangeCreateRequest(
                "session-v", 48, 72, VoiceRangeSourceMethod.OCTAVE_PICK);

        final CreateVoiceRangeCommand createVoiceRangeCommand = voiceRangeCreateRequest.toCommand();

        assertThat(createVoiceRangeCommand.sessionId()).isEqualTo("session-v");
        assertThat(createVoiceRangeCommand.lowestNoteMidi()).isEqualTo(48);
        assertThat(createVoiceRangeCommand.highestNoteMidi()).isEqualTo(72);
        assertThat(createVoiceRangeCommand.sourceMethod()).isEqualTo(VoiceRangeSourceMethod.OCTAVE_PICK);
    }
}
