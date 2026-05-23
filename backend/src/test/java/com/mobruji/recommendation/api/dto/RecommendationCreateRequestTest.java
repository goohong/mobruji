package com.mobruji.recommendation.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.recommendation.application.CreateRecommendationCommand;
import com.mobruji.song.domain.Mood;

class RecommendationCreateRequestTest {

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
}
