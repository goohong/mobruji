package com.mobruji.recommendation.api.dto;

import java.util.List;

import com.mobruji.recommendation.domain.FilterRelaxation;
import com.mobruji.recommendation.domain.SequenceRecommendationResult;
import com.mobruji.song.domain.Mood;

/**
 * 모임 사회자형(P-D) 시퀀스 추천 응답 DTO.
 *
 * <p>{@code persona}는 페르소나 식별자("P-D" 등, 설명 가능성 — persona-expansion-social-emotional.md §4). {@code stages}는
 * 자리 흐름 순서(워밍업 → 고조 → 마무리)대로의 단계별 추천 묶음이다. 각 단계는 고유 {@code requestId}를 가져, fe(#1601)는 단계별
 * 곡 피드백·재조회를 단일 추천과 같은 경로로 처리할 수 있다. 단계 안 {@code recommendations}는 단일 추천과 동일한
 * {@link RecommendedSongResponse} 형상이라 결과 카드 렌더를 재사용한다.
 */
public record SequenceRecommendationResponse(
        String persona,
        List<StageResponse> stages
) {

    public static SequenceRecommendationResponse from(final SequenceRecommendationResult result) {
        final List<StageResponse> stageResponses = result.stages().stream()
                .map(StageResponse::from)
                .toList();
        return new SequenceRecommendationResponse(result.persona().code(), stageResponses);
    }

    /**
     * 시퀀스 한 단계의 응답 — 단계 식별자/분위기/설명 + 단계의 추천 결과(요청 ID·완화 플래그·곡 리스트).
     */
    public record StageResponse(
            String stage,
            Mood mood,
            String stageReason,
            Long requestId,
            boolean relaxed,
            List<String> relaxedFilters,
            List<RecommendedSongResponse> recommendations
    ) {

        public static StageResponse from(final SequenceRecommendationResult.StageRecommendation stage) {
            final List<RecommendedSongResponse> recommendedSongResponses = stage.result().recommendations().stream()
                    .map(RecommendedSongResponse::from)
                    .toList();
            final List<String> relaxedFilters = stage.result().relaxedFilters().stream()
                    .map(FilterRelaxation::name)
                    .toList();
            return new StageResponse(
                    stage.stage().name(),
                    stage.mood(),
                    stage.stageReason(),
                    stage.result().requestId(),
                    stage.result().relaxed(),
                    relaxedFilters,
                    recommendedSongResponses);
        }
    }
}
