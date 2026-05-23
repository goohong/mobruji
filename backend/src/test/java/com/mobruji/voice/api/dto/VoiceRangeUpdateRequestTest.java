package com.mobruji.voice.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.voice.application.UpdateVoiceRangeCommand;
import com.mobruji.voice.domain.MidiRange;
import com.mobruji.voice.domain.VoiceRangeSourceMethod;

/**
 * VoiceRangeUpdateRequest 메타데이터 회귀 가드.
 *
 * <p>update 는 sessionId 를 path variable 로 받기 때문에 body 에는 포함되지 않는다.
 */
class VoiceRangeUpdateRequestTest {

    @Test
    @DisplayName("lowestNoteMidi/highestNoteMidi: @NotNull + @Min(LOWEST) + @Max(HIGHEST) (MidiRange 상수와 일치)")
    void midiRangeValidationMetadata_alignsWithDomainConstants() throws NoSuchMethodException {
        final Min lowestMin = VoiceRangeUpdateRequest.class
                .getDeclaredMethod("lowestNoteMidi").getAnnotation(Min.class);
        final Max lowestMax = VoiceRangeUpdateRequest.class
                .getDeclaredMethod("lowestNoteMidi").getAnnotation(Max.class);
        final NotNull lowestNotNull = VoiceRangeUpdateRequest.class
                .getDeclaredMethod("lowestNoteMidi").getAnnotation(NotNull.class);

        assertThat(lowestNotNull).isNotNull();
        assertThat(lowestMin.value()).isEqualTo(MidiRange.LOWEST_ALLOWED_MIDI);
        assertThat(lowestMax.value()).isEqualTo(MidiRange.HIGHEST_ALLOWED_MIDI);

        final Min highestMin = VoiceRangeUpdateRequest.class
                .getDeclaredMethod("highestNoteMidi").getAnnotation(Min.class);
        final Max highestMax = VoiceRangeUpdateRequest.class
                .getDeclaredMethod("highestNoteMidi").getAnnotation(Max.class);
        final NotNull highestNotNull = VoiceRangeUpdateRequest.class
                .getDeclaredMethod("highestNoteMidi").getAnnotation(NotNull.class);

        assertThat(highestNotNull).isNotNull();
        assertThat(highestMin.value()).isEqualTo(MidiRange.LOWEST_ALLOWED_MIDI);
        assertThat(highestMax.value()).isEqualTo(MidiRange.HIGHEST_ALLOWED_MIDI);
    }

    @Test
    @DisplayName("sourceMethod: @NotNull 메타데이터 존재")
    void sourceMethodValidationMetadata_present() throws NoSuchMethodException {
        assertThat(VoiceRangeUpdateRequest.class.getDeclaredMethod("sourceMethod").getAnnotation(NotNull.class))
                .isNotNull();
    }

    @Test
    @DisplayName("toCommand: 모든 필드를 UpdateVoiceRangeCommand 로 그대로 매핑 (sessionId 없음)")
    void toCommand_mapsAllFields() {
        final VoiceRangeUpdateRequest voiceRangeUpdateRequest = new VoiceRangeUpdateRequest(
                50, 80, VoiceRangeSourceMethod.MIC_MEASURE);

        final UpdateVoiceRangeCommand updateVoiceRangeCommand = voiceRangeUpdateRequest.toCommand();

        assertThat(updateVoiceRangeCommand.lowestNoteMidi()).isEqualTo(50);
        assertThat(updateVoiceRangeCommand.highestNoteMidi()).isEqualTo(80);
        assertThat(updateVoiceRangeCommand.sourceMethod()).isEqualTo(VoiceRangeSourceMethod.MIC_MEASURE);
    }
}
