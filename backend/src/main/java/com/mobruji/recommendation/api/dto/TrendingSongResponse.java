package com.mobruji.recommendation.api.dto;

import com.mobruji.recommendation.domain.TrendingSong;
import com.mobruji.song.api.dto.SongResponse;

/**
 * 트렌딩 인기곡 1건 응답 DTO (#1488).
 *
 * <ul>
 * <li>{@code rankPosition} — 1부터의 트렌딩 순위.</li>
 * <li>{@code appearanceCount} — 집계 기간 내 추천 결과에 등장한 횟수.</li>
 * <li>{@code popularityScore} — rank 감쇠 인기도 합 (상위 노출일수록 가중 ↑).</li>
 * </ul>
 */
public record TrendingSongResponse(
        SongResponse song,
        int rankPosition,
        long appearanceCount,
        double popularityScore
) {

    public static TrendingSongResponse from(final TrendingSong trendingSong) {
        return new TrendingSongResponse(
                SongResponse.from(trendingSong.song()),
                trendingSong.rankPosition(),
                trendingSong.appearanceCount(),
                trendingSong.popularityScore());
    }
}
