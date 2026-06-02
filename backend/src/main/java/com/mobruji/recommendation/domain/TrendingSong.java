package com.mobruji.recommendation.domain;

import java.util.Objects;

import com.mobruji.song.domain.Song;

/**
 * 트렌딩 결과 1건 (#1488): 인기곡 + 순위/등장 횟수/인기도. application 이 곡 메타데이터를 join 한 뒤 만드는
 * 도메인 값 객체로, api.dto 의존 없이 결과를 전달한다 (ADR 0005 §A-7 — 결과 컨테이너는 domain).
 */
public record TrendingSong(
        Song song,
        int rankPosition,
        long appearanceCount,
        double popularityScore
) {

    public TrendingSong {
        Objects.requireNonNull(song, "song must not be null");
        if (rankPosition < 1) {
            throw new IllegalArgumentException("rankPosition must be >= 1: " + rankPosition);
        }
        if (appearanceCount < 1) {
            throw new IllegalArgumentException("appearanceCount must be >= 1: " + appearanceCount);
        }
    }
}
