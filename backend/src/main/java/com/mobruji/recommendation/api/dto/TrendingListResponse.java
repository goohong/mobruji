package com.mobruji.recommendation.api.dto;

import java.util.List;

import com.mobruji.recommendation.domain.TrendingSong;
import com.mobruji.song.domain.Mood;

/**
 * 트렌딩(다른 사용자 인기곡) 응답 wrapper (#1488).
 *
 * <p>배열을 직접 노출하지 않고 객체로 감싸 적용된 집계 조건(기간/분위기/음역대)을 echo 한다 — fe 가 "어떤
 * 기준으로 뜬 곡인지"를 표시하고, 향후 페이지네이션/메타 추가 시 키 변경 없이 진화 가능.
 *
 * <p>{@code trendingSongs} 는 인기도 DESC 순위(1위부터)를 따른다.
 */
public record TrendingListResponse(
        int periodDays,
        Mood mood,
        Integer voiceRangeLow,
        Integer voiceRangeHigh,
        List<TrendingSongResponse> trendingSongs
) {

    public static TrendingListResponse of(
            final int periodDays,
            final Mood mood,
            final Integer voiceRangeLow,
            final Integer voiceRangeHigh,
            final List<TrendingSong> trendingSongs) {
        return new TrendingListResponse(
                periodDays,
                mood,
                voiceRangeLow,
                voiceRangeHigh,
                trendingSongs.stream().map(TrendingSongResponse::from).toList());
    }
}
