package com.mobruji.recommendation.domain;

import java.util.Objects;

import com.mobruji.song.domain.Song;

/**
 * 추천 결과 1건 (한 곡 + 점수 + 매칭 사유 + 랭킹). application 계층이 api.dto에 의존하지 않도록
 * domain 레이어에 두는 값 객체. 영속 엔티티({@link Recommendation})와 응답 형태({@code RecommendedSongResponse})를
 * 잇는 중간 표현.
 */
public record ScoredRecommendation(
        Song song,
        double score,
        String matchReason,
        int rankPosition
) {

    public ScoredRecommendation {
        Objects.requireNonNull(song, "song must not be null");
        Objects.requireNonNull(matchReason, "matchReason must not be null");
        if (rankPosition < 1) {
            throw new IllegalArgumentException("rankPosition must be >= 1: " + rankPosition);
        }
    }
}
