package com.mobruji.recommendation.api.dto;

import java.util.List;

import com.mobruji.recommendation.domain.RecommendationResult;

public record RecommendationResponse(
        Long requestId,
        List<RecommendedSongResponse> recommendations
) {

    public static RecommendationResponse from(final RecommendationResult recommendationResult) {
        final List<RecommendedSongResponse> recommendedSongResponses = recommendationResult.recommendations().stream()
                .map(RecommendedSongResponse::from)
                .toList();
        return new RecommendationResponse(recommendationResult.requestId(), recommendedSongResponses);
    }
}
