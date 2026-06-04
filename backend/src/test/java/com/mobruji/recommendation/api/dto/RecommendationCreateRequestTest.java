package com.mobruji.recommendation.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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

import com.mobruji.recommendation.application.CreateRecommendationCommand;
import com.mobruji.song.domain.Mood;
import com.mobruji.user.domain.SessionIdPatterns;

/**
 * RecommendationCreateRequest 메타데이터 + Validator 실행 회귀 가드.
 *
 * <p>#666 에서 Validator 가드 추가. #948 후속에서 {@code SessionIdPatterns.UUID_V4} {@code @Pattern} 강제
 * — fixture sessionId 는 UUIDv4 로 통일. #1549 에서 세션 단위 자동 중복 회피 플래그
 * {@code excludeSessionHistory} 추가.
 */
class RecommendationCreateRequestTest {

    private static final String VALID_SESSION_ID = "550e8400-e29b-41d4-a716-446655449401";
    private static final String VALID_SESSION_ID_ALT_A = "550e8400-e29b-41d4-a716-446655449402";
    private static final String VALID_SESSION_ID_ALT_B = "550e8400-e29b-41d4-a716-446655449403";

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
    @DisplayName("excludeSongIdsOrEmpty: null 입력은 빈 리스트로 정규화")
    void excludeSongIdsOrEmpty_nullBecomesEmpty() {
        final RecommendationCreateRequest recommendationCreateRequest = new RecommendationCreateRequest(
                VALID_SESSION_ID, 48, 72, Mood.UPBEAT, 120, null, null, null, null);

        assertThat(recommendationCreateRequest.excludeSongIdsOrEmpty()).isEmpty();
    }

    @Test
    @DisplayName("excludeSongIdsOrEmpty: 비어있지 않은 입력은 그대로 통과 (순서/중복 보존)")
    void excludeSongIdsOrEmpty_preservesOrderAndDuplicates() {
        final List<Long> excludeSongIds = List.of(3L, 1L, 3L, 2L);
        final RecommendationCreateRequest recommendationCreateRequest = new RecommendationCreateRequest(
                VALID_SESSION_ID, 48, 72, Mood.CALM, null, null, null, excludeSongIds, null);

        assertThat(recommendationCreateRequest.excludeSongIdsOrEmpty())
                .containsExactly(3L, 1L, 3L, 2L);
    }

    @Test
    @DisplayName("excludeSessionHistoryOrFalse: null/false 는 false, true 는 true 로 정규화")
    void excludeSessionHistoryOrFalse_normalizesNull() {
        final RecommendationCreateRequest nullFlag = new RecommendationCreateRequest(
                VALID_SESSION_ID, 48, 72, Mood.UPBEAT, null, null, null, null, null);
        final RecommendationCreateRequest falseFlag = new RecommendationCreateRequest(
                VALID_SESSION_ID, 48, 72, Mood.UPBEAT, null, null, null, null, false);
        final RecommendationCreateRequest trueFlag = new RecommendationCreateRequest(
                VALID_SESSION_ID, 48, 72, Mood.UPBEAT, null, null, null, null, true);

        assertThat(nullFlag.excludeSessionHistoryOrFalse()).isFalse();
        assertThat(falseFlag.excludeSessionHistoryOrFalse()).isFalse();
        assertThat(trueFlag.excludeSessionHistoryOrFalse()).isTrue();
    }

    @Test
    @DisplayName("toCommand: DTO 필드를 그대로 CreateRecommendationCommand 로 매핑하고 excludeSongIds 는 정규화")
    void toCommand_mapsAllFieldsAndNormalizesExcludeSongIds() {
        final RecommendationCreateRequest recommendationCreateRequest = new RecommendationCreateRequest(
                VALID_SESSION_ID_ALT_A, 50, 80, Mood.UPBEAT, 140, null, null, List.of(10L, 20L), true);

        final CreateRecommendationCommand createRecommendationCommand = recommendationCreateRequest.toCommand();

        assertThat(createRecommendationCommand.sessionId()).isEqualTo(VALID_SESSION_ID_ALT_A);
        assertThat(createRecommendationCommand.voiceRangeLow()).isEqualTo(50);
        assertThat(createRecommendationCommand.voiceRangeHigh()).isEqualTo(80);
        assertThat(createRecommendationCommand.mood()).isEqualTo(Mood.UPBEAT);
        assertThat(createRecommendationCommand.preferredBpm()).isEqualTo(140);
        assertThat(createRecommendationCommand.excludeSongIds()).containsExactly(10L, 20L);
        assertThat(createRecommendationCommand.excludeSessionHistory()).isTrue();
    }

    @Test
    @DisplayName("toCommand: excludeSongIds null 입력도 빈 리스트로 정규화되어 매핑")
    void toCommand_nullExcludeSongIdsBecomesEmpty() {
        final RecommendationCreateRequest recommendationCreateRequest = new RecommendationCreateRequest(
                VALID_SESSION_ID_ALT_B, 48, 72, null, null, null, null, null, null);

        final CreateRecommendationCommand createRecommendationCommand = recommendationCreateRequest.toCommand();

        assertThat(createRecommendationCommand.excludeSongIds()).isEmpty();
        assertThat(createRecommendationCommand.mood()).isNull();
        assertThat(createRecommendationCommand.preferredBpm()).isNull();
        assertThat(createRecommendationCommand.excludeSessionHistory()).isFalse();
    }

    @Test
    @DisplayName("validation 어노테이션 메타데이터: sessionId @NotBlank @Size(max=64) @Pattern(UUIDv4), voiceRange @NotNull @Min(12) @Max(119), preferredBpm @Min(30) @Max(300)")
    void validationAnnotations_present() throws NoSuchMethodException {
        // record 의 component 어노테이션은 record 컴포넌트 → accessor 메서드로 전파된다.
        assertThat(RecommendationCreateRequest.class.getDeclaredMethod("sessionId").getAnnotation(NotBlank.class))
                .isNotNull();
        final Size sessionIdSize = RecommendationCreateRequest.class
                .getDeclaredMethod("sessionId").getAnnotation(Size.class);
        assertThat(sessionIdSize).isNotNull();
        assertThat(sessionIdSize.max()).isEqualTo(64);

        final Pattern sessionIdPattern = RecommendationCreateRequest.class
                .getDeclaredMethod("sessionId").getAnnotation(Pattern.class);
        assertThat(sessionIdPattern).isNotNull();
        assertThat(sessionIdPattern.regexp()).isEqualTo(SessionIdPatterns.UUID_V4);

        final Min voiceRangeLowMin = RecommendationCreateRequest.class
                .getDeclaredMethod("voiceRangeLow").getAnnotation(Min.class);
        final Max voiceRangeLowMax = RecommendationCreateRequest.class
                .getDeclaredMethod("voiceRangeLow").getAnnotation(Max.class);
        final NotNull voiceRangeLowNotNull = RecommendationCreateRequest.class
                .getDeclaredMethod("voiceRangeLow").getAnnotation(NotNull.class);
        assertThat(voiceRangeLowMin.value()).isEqualTo(12L);
        assertThat(voiceRangeLowMax.value()).isEqualTo(119L);
        assertThat(voiceRangeLowNotNull).isNotNull();

        final Min preferredBpmMin = RecommendationCreateRequest.class
                .getDeclaredMethod("preferredBpm").getAnnotation(Min.class);
        final Max preferredBpmMax = RecommendationCreateRequest.class
                .getDeclaredMethod("preferredBpm").getAnnotation(Max.class);
        assertThat(preferredBpmMin.value()).isEqualTo(30L);
        assertThat(preferredBpmMax.value()).isEqualTo(300L);
    }

    @Test
    @DisplayName("Validator 실행: 정상 입력(UUIDv4 sessionId + MIDI/BPM 경계값) → violation 없음 (#666)")
    void validator_validBoundaryInput_passes() {
        final RecommendationCreateRequest recommendationCreateRequest = new RecommendationCreateRequest(
                VALID_SESSION_ID, 12, 119, Mood.UPBEAT, 30, null, null, List.of(1L), true);

        final Set<ConstraintViolation<RecommendationCreateRequest>> violations = validator.validate(
                recommendationCreateRequest);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Validator 실행: 선택 필드(mood/preferredBpm/excludeSongIds) 모두 null → violation 없음")
    void validator_optionalFieldsAllNull_passes() {
        final RecommendationCreateRequest recommendationCreateRequest = new RecommendationCreateRequest(
                VALID_SESSION_ID, 48, 72, null, null, null, null, null, null);

        final Set<ConstraintViolation<RecommendationCreateRequest>> violations = validator.validate(
                recommendationCreateRequest);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Validator 실행: sessionId blank / MIDI 범위 외 / BPM 범위 외 / 필수 null → violation 발생 (#666)")
    void validator_invalidInputs_produceViolations() {
        assertThat(validator.validate(new RecommendationCreateRequest(
                "", 48, 72, null, null, null, null, null, null))).isNotEmpty();
        assertThat(validator.validate(new RecommendationCreateRequest(
                VALID_SESSION_ID, 11, 72, null, null, null, null, null, null))).isNotEmpty();
        assertThat(validator.validate(new RecommendationCreateRequest(
                VALID_SESSION_ID, 48, 120, null, null, null, null, null, null))).isNotEmpty();
        assertThat(validator.validate(new RecommendationCreateRequest(
                VALID_SESSION_ID, null, 72, null, null, null, null, null, null))).isNotEmpty();
        assertThat(validator.validate(new RecommendationCreateRequest(
                VALID_SESSION_ID, 48, null, null, null, null, null, null, null))).isNotEmpty();
        assertThat(validator.validate(new RecommendationCreateRequest(
                VALID_SESSION_ID, 48, 72, null, 29, null, null, null, null))).isNotEmpty();
        assertThat(validator.validate(new RecommendationCreateRequest(
                VALID_SESSION_ID, 48, 72, null, 301, null, null, null, null))).isNotEmpty();
    }

    @Test
    @DisplayName("Validator 실행 (#948): sessionId 비-UUIDv4 형식 → @Pattern violation")
    void validator_nonUuidV4SessionId_violates() {
        // SessionRotateRequest / VoiceRangeCreateRequest 와 동일한 형식 강제.
        assertThat(validator.validate(new RecommendationCreateRequest(
                "session-1", 48, 72, null, null, null, null, null, null))).isNotEmpty();
        assertThat(validator.validate(new RecommendationCreateRequest(
                "k6-load-1716543210-3", 48, 72, null, null, null, null, null, null))).isNotEmpty();
        // 대문자 UUID 거부
        assertThat(validator.validate(new RecommendationCreateRequest(
                "550E8400-E29B-41D4-A716-446655449401", 48, 72, null, null, null, null, null, null))).isNotEmpty();
        // 65자 임의 string
        assertThat(validator.validate(new RecommendationCreateRequest(
                "r".repeat(65), 48, 72, null, null, null, null, null, null))).isNotEmpty();
        // 유효 UUIDv4 → violation 없음 (대조군)
        assertThat(validator.validate(new RecommendationCreateRequest(
                VALID_SESSION_ID, 48, 72, null, null, null, null, null, null))).isEmpty();
    }
}
