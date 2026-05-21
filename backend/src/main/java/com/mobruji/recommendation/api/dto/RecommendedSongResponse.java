package com.mobruji.recommendation.api.dto;

import com.mobruji.song.api.dto.SongResponse;

public record RecommendedSongResponse(
        SongResponse song,
        double score,
        String matchReason,
        int rankPosition
) {
}
