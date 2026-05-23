package com.mobruji.voice.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.voice.application.UpdateVoiceRangeCommand;
import com.mobruji.voice.domain.MidiRange;
import com.mobruji.voice.domain.VoiceRangeSourceMethod;

/**
 * VoiceRangeUpdateRequest 메타데이터 + Validator 실행 회귀 가드.
 *
 * <p>update 는 sessionId 를 path variable 로 받기 때문에 body 에는 포함되지 않는다.
 * Validator 실행 가드는 #666 에서 추가.
 */
class VoiceRangeUpdateRequestTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        validatorFactory.close();
    }

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

    @Test
    @DisplayName("Validator 실행: 정상 입력(MIDI 경계값) → violation 없음")
    void validator_validBoundaryInput_passes() {
        final VoiceRangeUpdateRequest voiceRangeUpdateRequest = new VoiceRangeUpdateRequest(
                MidiRange.LOWEST_ALLOWED_MIDI,
                MidiRange.HIGHEST_ALLOWED_MIDI,
                VoiceRangeSourceMethod.MIC_MEASURE);

        final Set<ConstraintViolation<VoiceRangeUpdateRequest>> violations = validator.validate(
                voiceRangeUpdateRequest);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Validator 실행: MIDI 범위 외 / null / sourceMethod null → violation 발생")
    void validator_invalidInputs_produceViolations() {
        final int belowLowest = MidiRange.LOWEST_ALLOWED_MIDI - 1;
        final int aboveHighest = MidiRange.HIGHEST_ALLOWED_MIDI + 1;
        assertThat(validator.validate(new VoiceRangeUpdateRequest(
                belowLowest, 80, VoiceRangeSourceMethod.MIC_MEASURE))).isNotEmpty();
        assertThat(validator.validate(new VoiceRangeUpdateRequest(
                50, aboveHighest, VoiceRangeSourceMethod.MIC_MEASURE))).isNotEmpty();
        assertThat(validator.validate(new VoiceRangeUpdateRequest(
                null, 80, VoiceRangeSourceMethod.MIC_MEASURE))).isNotEmpty();
        assertThat(validator.validate(new VoiceRangeUpdateRequest(
                50, null, VoiceRangeSourceMethod.MIC_MEASURE))).isNotEmpty();
        assertThat(validator.validate(new VoiceRangeUpdateRequest(
                50, 80, null))).isNotEmpty();
    }
}
