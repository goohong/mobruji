package com.mobruji.recommendation.dto;

import java.util.List;

public record RecommendationResponse(
        Long requestId,
        List<RecommendedSongResponse> recommendations
) {
}
