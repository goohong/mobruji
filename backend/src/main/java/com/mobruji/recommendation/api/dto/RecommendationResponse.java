package com.mobruji.recommendation.api.dto;

import java.util.List;

import com.mobruji.recommendation.domain.FilterRelaxation;
import com.mobruji.recommendation.domain.RecommendationResult;

/**
 * 추천 결과 응답 DTO.
 *
 * <p>{@code relaxed}(#1668)는 결과 0건(빈 화면)을 막기 위해 제외 필터를 완화해 채운 결과인지 여부, {@code relaxedFilters}는
 * 완화된 필터 코드({@link FilterRelaxation} 이름) 목록이다. FE 는 {@code relaxed == true}일 때 "정확히 맞는 곡이 부족해
 * 가까운 곡을 보여드려요" 안내를 띄운다.
 */
public record RecommendationResponse(
        Long requestId,
        List<RecommendedSongResponse> recommendations,
        boolean relaxed,
        List<String> relaxedFilters
) {

    public static RecommendationResponse from(final RecommendationResult recommendationResult) {
        final List<RecommendedSongResponse> recommendedSongResponses = recommendationResult.recommendations().stream()
                .map(RecommendedSongResponse::from)
                .toList();
        final List<String> relaxedFilters = recommendationResult.relaxedFilters().stream()
                .map(FilterRelaxation::name)
                .toList();
        return new RecommendationResponse(
                recommendationResult.requestId(),
                recommendedSongResponses,
                recommendationResult.relaxed(),
                relaxedFilters);
    }
}
