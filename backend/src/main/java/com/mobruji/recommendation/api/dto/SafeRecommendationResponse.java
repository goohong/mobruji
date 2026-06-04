package com.mobruji.recommendation.api.dto;

import java.util.List;

import com.mobruji.recommendation.domain.FilterRelaxation;
import com.mobruji.recommendation.domain.SafeRecommendationResult;

/**
 * 안전곡형(P-E) 추천 응답 DTO.
 *
 * <p>{@code persona}는 페르소나 식별자("P-E", 설명 가능성 — persona-expansion-social-emotional.md §4). {@code requestId}는
 * 단일 추천과 같은 경로로 영속된 추천 요청 ID 라 fe(#1600)는 곡 피드백·재조회를 단일 추천과 같은 경로로 처리할 수 있다.
 * {@code relaxed}/{@code relaxedFilters}는 0건 fallback(#1668) 완화 신호다.
 *
 * <p>{@code recommendations}는 안전곡 강편향(EASY 우위)으로 재정렬한 곡 묶음이다. 각 항목은 단일 추천과 같은 곡 형상
 * ({@link RecommendedSongResponse}, 결과 카드 렌더 재사용) + 페르소나별 "안심 포인트"({@code safetyReason})를 함께 노출한다.
 */
public record SafeRecommendationResponse(
        String persona,
        Long requestId,
        boolean relaxed,
        List<String> relaxedFilters,
        List<SafeSongResponse> recommendations
) {

    public static SafeRecommendationResponse from(final SafeRecommendationResult result) {
        final List<SafeSongResponse> safeSongResponses = result.recommendations().stream()
                .map(SafeSongResponse::from)
                .toList();
        final List<String> relaxedFilters = result.relaxedFilters().stream()
                .map(FilterRelaxation::name)
                .toList();
        return new SafeRecommendationResponse(
                result.persona().code(), result.requestId(), result.relaxed(), relaxedFilters, safeSongResponses);
    }

    /**
     * 안전곡 추천 1건의 응답 — 단일 추천과 같은 곡 형상 + "안심 포인트" 사유.
     */
    public record SafeSongResponse(
            String safetyReason,
            RecommendedSongResponse recommendation
    ) {

        public static SafeSongResponse from(final SafeRecommendationResult.SafeRecommendation safeRecommendation) {
            return new SafeSongResponse(
                    safeRecommendation.safetyReason(),
                    RecommendedSongResponse.from(safeRecommendation.recommendation()));
        }
    }
}
