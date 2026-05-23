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
import jakarta.validation.constraints.Size;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.recommendation.application.CreateRecommendationCommand;
import com.mobruji.song.domain.Mood;

class RecommendationCreateRequestTest {

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
                "session-1", 48, 72, Mood.UPBEAT, 120, null);

        assertThat(recommendationCreateRequest.excludeSongIdsOrEmpty()).isEmpty();
    }

    @Test
    @DisplayName("excludeSongIdsOrEmpty: 비어있지 않은 입력은 그대로 통과 (순서/중복 보존)")
    void excludeSongIdsOrEmpty_preservesOrderAndDuplicates() {
        final List<Long> excludeSongIds = List.of(3L, 1L, 3L, 2L);
        final RecommendationCreateRequest recommendationCreateRequest = new RecommendationCreateRequest(
                "session-1", 48, 72, Mood.CALM, null, excludeSongIds);

        assertThat(recommendationCreateRequest.excludeSongIdsOrEmpty())
                .containsExactly(3L, 1L, 3L, 2L);
    }

    @Test
    @DisplayName("toCommand: DTO 필드를 그대로 CreateRecommendationCommand 로 매핑하고 excludeSongIds 는 정규화")
    void toCommand_mapsAllFieldsAndNormalizesExcludeSongIds() {
        final RecommendationCreateRequest recommendationCreateRequest = new RecommendationCreateRequest(
                "session-2", 50, 80, Mood.UPBEAT, 140, List.of(10L, 20L));

        final CreateRecommendationCommand createRecommendationCommand = recommendationCreateRequest.toCommand();

        assertThat(createRecommendationCommand.sessionId()).isEqualTo("session-2");
        assertThat(createRecommendationCommand.voiceRangeLow()).isEqualTo(50);
        assertThat(createRecommendationCommand.voiceRangeHigh()).isEqualTo(80);
        assertThat(createRecommendationCommand.mood()).isEqualTo(Mood.UPBEAT);
        assertThat(createRecommendationCommand.preferredBpm()).isEqualTo(140);
        assertThat(createRecommendationCommand.excludeSongIds()).containsExactly(10L, 20L);
    }

    @Test
    @DisplayName("toCommand: excludeSongIds null 입력도 빈 리스트로 정규화되어 매핑")
    void toCommand_nullExcludeSongIdsBecomesEmpty() {
        final RecommendationCreateRequest recommendationCreateRequest = new RecommendationCreateRequest(
                "session-3", 48, 72, null, null, null);

        final CreateRecommendationCommand createRecommendationCommand = recommendationCreateRequest.toCommand();

        assertThat(createRecommendationCommand.excludeSongIds()).isEmpty();
        assertThat(createRecommendationCommand.mood()).isNull();
        assertThat(createRecommendationCommand.preferredBpm()).isNull();
    }

    @Test
    @DisplayName("validation 어노테이션 메타데이터: sessionId @NotBlank @Size(max=64), voiceRange @NotNull @Min(12) @Max(119), preferredBpm @Min(30) @Max(300)")
    void validationAnnotations_present() throws NoSuchMethodException {
        // record 의 component 어노테이션은 record 컴포넌트 → accessor 메서드로 전파된다.
        assertThat(RecommendationCreateRequest.class.getDeclaredMethod("sessionId").getAnnotation(NotBlank.class))
                .isNotNull();
        final Size sessionIdSize = RecommendationCreateRequest.class
                .getDeclaredMethod("sessionId").getAnnotation(Size.class);
        assertThat(sessionIdSize).isNotNull();
        assertThat(sessionIdSize.max()).isEqualTo(64);

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
    @DisplayName("Validator 실행: 정상 입력(sessionId 64자 + MIDI/BPM 경계값) → violation 없음 (#666)")
    void validator_validBoundaryInput_passes() {
        final String sessionId64 = "r".repeat(64);
        final RecommendationCreateRequest recommendationCreateRequest = new RecommendationCreateRequest(
                sessionId64, 12, 119, Mood.UPBEAT, 30, List.of(1L));

        final Set<ConstraintViolation<RecommendationCreateRequest>> violations = validator.validate(
                recommendationCreateRequest);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Validator 실행: 선택 필드(mood/preferredBpm/excludeSongIds) 모두 null → violation 없음")
    void validator_optionalFieldsAllNull_passes() {
        final RecommendationCreateRequest recommendationCreateRequest = new RecommendationCreateRequest(
                "session-1", 48, 72, null, null, null);

        final Set<ConstraintViolation<RecommendationCreateRequest>> violations = validator.validate(
                recommendationCreateRequest);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Validator 실행: sessionId 65자 / blank / MIDI 범위 외 / BPM 범위 외 / 필수 null → violation 발생 (#666)")
    void validator_invalidInputs_produceViolations() {
        assertThat(validator.validate(new RecommendationCreateRequest(
                "r".repeat(65), 48, 72, null, null, null))).isNotEmpty();
        assertThat(validator.validate(new RecommendationCreateRequest(
                "", 48, 72, null, null, null))).isNotEmpty();
        assertThat(validator.validate(new RecommendationCreateRequest(
                "s", 11, 72, null, null, null))).isNotEmpty();
        assertThat(validator.validate(new RecommendationCreateRequest(
                "s", 48, 120, null, null, null))).isNotEmpty();
        assertThat(validator.validate(new RecommendationCreateRequest(
                "s", null, 72, null, null, null))).isNotEmpty();
        assertThat(validator.validate(new RecommendationCreateRequest(
                "s", 48, null, null, null, null))).isNotEmpty();
        assertThat(validator.validate(new RecommendationCreateRequest(
                "s", 48, 72, null, 29, null))).isNotEmpty();
        assertThat(validator.validate(new RecommendationCreateRequest(
                "s", 48, 72, null, 301, null))).isNotEmpty();
    }
}
