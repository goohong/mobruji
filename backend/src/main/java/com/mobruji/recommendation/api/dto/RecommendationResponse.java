package com.mobruji.recommendation.api.dto;

import java.util.List;

public record RecommendationResponse(
        Long requestId,
        List<RecommendedSongResponse> recommendations
) {
}
