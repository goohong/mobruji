package com.mobruji.voice.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.user.domain.SessionIdPatterns;
import com.mobruji.voice.application.CreateVoiceRangeCommand;
import com.mobruji.voice.domain.MidiRange;
import com.mobruji.voice.domain.VoiceRangeSourceMethod;

/**
 * VoiceRangeCreateRequest 메타데이터 + Validator 실행 회귀 가드.
 *
 * <p>sessionId 의 @Size(max=64) 는 DB column length 와 정합되어야 한다 (V1/V5 migration).
 * Validator 실행 가드는 #666 에서 추가. #948 에서 {@code SessionIdPatterns.UUID_V4} {@code @Pattern}
 * 강제 — fixture 는 UUIDv4 로 통일.
 */
class VoiceRangeCreateRequestTest {

    private static final String VALID_SESSION_ID = "550e8400-e29b-41d4-a716-446655449101";
    private static final String VALID_SESSION_ID_ALT = "550e8400-e29b-41d4-a716-446655449102";

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
    @DisplayName("sessionId: @NotBlank + @Size(max=64) + @Pattern(UUIDv4) 메타데이터 존재")
    void sessionIdValidationMetadata_present() throws NoSuchMethodException {
        assertThat(VoiceRangeCreateRequest.class.getDeclaredMethod("sessionId").getAnnotation(NotBlank.class))
                .isNotNull();

        final Size sessionIdSize = VoiceRangeCreateRequest.class
                .getDeclaredMethod("sessionId").getAnnotation(Size.class);
        assertThat(sessionIdSize).isNotNull();
        assertThat(sessionIdSize.max()).isEqualTo(64);

        final Pattern sessionIdPattern = VoiceRangeCreateRequest.class
                .getDeclaredMethod("sessionId").getAnnotation(Pattern.class);
        assertThat(sessionIdPattern).isNotNull();
        assertThat(sessionIdPattern.regexp()).isEqualTo(SessionIdPatterns.UUID_V4);
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
                VALID_SESSION_ID, 48, 72, VoiceRangeSourceMethod.OCTAVE_PICK);

        final CreateVoiceRangeCommand createVoiceRangeCommand = voiceRangeCreateRequest.toCommand();

        assertThat(createVoiceRangeCommand.sessionId()).isEqualTo(VALID_SESSION_ID);
        assertThat(createVoiceRangeCommand.lowestNoteMidi()).isEqualTo(48);
        assertThat(createVoiceRangeCommand.highestNoteMidi()).isEqualTo(72);
        assertThat(createVoiceRangeCommand.sourceMethod()).isEqualTo(VoiceRangeSourceMethod.OCTAVE_PICK);
    }

    @Test
    @DisplayName("Validator 실행: 정상 입력(UUIDv4 sessionId + MIDI 경계값) → violation 없음")
    void validator_validBoundaryInput_passes() {
        final VoiceRangeCreateRequest voiceRangeCreateRequest = new VoiceRangeCreateRequest(
                VALID_SESSION_ID,
                MidiRange.LOWEST_ALLOWED_MIDI,
                MidiRange.HIGHEST_ALLOWED_MIDI,
                VoiceRangeSourceMethod.OCTAVE_PICK);

        final Set<ConstraintViolation<VoiceRangeCreateRequest>> violations = validator.validate(
                voiceRangeCreateRequest);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Validator 실행: sessionId 65자 / MIDI 범위 외 / sourceMethod null → violation 발생")
    void validator_invalidInputs_produceViolations() {
        final int belowLowest = MidiRange.LOWEST_ALLOWED_MIDI - 1;
        final int aboveHighest = MidiRange.HIGHEST_ALLOWED_MIDI + 1;
        assertThat(validator.validate(new VoiceRangeCreateRequest(
                "v".repeat(65), 48, 72, VoiceRangeSourceMethod.OCTAVE_PICK))).isNotEmpty();
        assertThat(validator.validate(new VoiceRangeCreateRequest(
                "", 48, 72, VoiceRangeSourceMethod.OCTAVE_PICK))).isNotEmpty();
        assertThat(validator.validate(new VoiceRangeCreateRequest(
                VALID_SESSION_ID, belowLowest, 72, VoiceRangeSourceMethod.OCTAVE_PICK))).isNotEmpty();
        assertThat(validator.validate(new VoiceRangeCreateRequest(
                VALID_SESSION_ID, 48, aboveHighest, VoiceRangeSourceMethod.OCTAVE_PICK))).isNotEmpty();
        assertThat(validator.validate(new VoiceRangeCreateRequest(
                VALID_SESSION_ID_ALT, 48, 72, null))).isNotEmpty();
    }

    @Test
    @DisplayName("Validator 실행 (#948): sessionId 비-UUIDv4 형식 → @Pattern violation")
    void validator_nonUuidV4SessionId_violates() {
        // SessionRotateRequest 와 동일한 형식 강제 — k6 비-UUIDv4 sessionId 회귀 가드.
        assertThat(validator.validate(new VoiceRangeCreateRequest(
                "session-v", 48, 72, VoiceRangeSourceMethod.OCTAVE_PICK))).isNotEmpty();
        assertThat(validator.validate(new VoiceRangeCreateRequest(
                "k6-load-1716543210-3", 48, 72, VoiceRangeSourceMethod.OCTAVE_PICK))).isNotEmpty();
        assertThat(validator.validate(new VoiceRangeCreateRequest(
                "550E8400-E29B-41D4-A716-446655449101", 48, 72,
                VoiceRangeSourceMethod.OCTAVE_PICK))).isNotEmpty();
        // 유효 UUIDv4 → violation 없음 (대조군)
        assertThat(validator.validate(new VoiceRangeCreateRequest(
                VALID_SESSION_ID, 48, 72, VoiceRangeSourceMethod.OCTAVE_PICK))).isEmpty();
    }
}
