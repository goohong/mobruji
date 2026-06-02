package com.mobruji.recommendation.api.dto;

import com.mobruji.recommendation.domain.ScoredRecommendation;
import com.mobruji.song.api.dto.SongResponse;

/**
 * 추천 곡 1건 응답 DTO.
 *
 * <p>{@code voiceFit}(0~1) / {@code voiceFitReason}은 "왜 이 곡?"(설명 가능성, #1484)을 전면 노출하는 필드로,
 * breakdown 의 {@code rangeFit} 신호를 곡별 음역 적합도 점수와 짧은 한국어 사유로 풀어 준다. breakdown 이 없는
 * 과거 추천 재조회 경로에서는 {@code breakdown}과 함께 둘 다 {@code null}.
 */
public record RecommendedSongResponse(
        SongResponse song,
        double score,
        String matchReason,
        Double voiceFit,
        String voiceFitReason,
        int rankPosition,
        ScoreBreakdownResponse breakdown
) {

    public static RecommendedSongResponse from(final ScoredRecommendation scoredRecommendation) {
        return new RecommendedSongResponse(
                SongResponse.from(scoredRecommendation.song()),
                scoredRecommendation.score(),
                scoredRecommendation.matchReason(),
                scoredRecommendation.voiceFit(),
                scoredRecommendation.voiceFitReason(),
                scoredRecommendation.rankPosition(),
                ScoreBreakdownResponse.from(scoredRecommendation.breakdown()));
    }
}
