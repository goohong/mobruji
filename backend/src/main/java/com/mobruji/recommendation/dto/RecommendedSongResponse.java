package com.mobruji.recommendation.dto;

import com.mobruji.song.dto.SongResponse;

public record RecommendedSongResponse(
        SongResponse song,
        double score,
        String matchReason,
        int rankPosition
) {
}
