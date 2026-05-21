package com.mobruji.recommendation.api.dto;

import com.mobruji.recommendation.domain.ScoredRecommendation;
import com.mobruji.song.api.dto.SongResponse;

public record RecommendedSongResponse(
        SongResponse song,
        double score,
        String matchReason,
        int rankPosition
) {

    public static RecommendedSongResponse from(final ScoredRecommendation scoredRecommendation) {
        return new RecommendedSongResponse(
                SongResponse.from(scoredRecommendation.song()),
                scoredRecommendation.score(),
                scoredRecommendation.matchReason(),
                scoredRecommendation.rankPosition());
    }
}
