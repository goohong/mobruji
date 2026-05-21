package com.mobruji.recommendation.domain;

import java.util.Objects;

import com.mobruji.song.domain.Song;

/**
 * 추천 결과 1건 (한 곡 + 점수 + 매칭 사유 + 랭킹). application 계층이 api.dto에 의존하지 않도록
 * domain 레이어에 두는 값 객체. 영속 엔티티({@link Recommendation})와 응답 형태({@code RecommendedSongResponse})를
 * 잇는 중간 표현.
 *
 * <p>{@code breakdown}은 score 산정 시점의 raw 신호(0~1) 5종을 담아 응답에 그대로 노출한다. 영속 엔티티에는 저장되지 않아
 * {@code readById} 경로(과거 추천 재조회)에서는 {@code null}이 들어온다. UI는 null이면 펼침 영역을 숨기는 식으로 동작한다.
 */
public record ScoredRecommendation(
        Song song,
        double score,
        String matchReason,
        int rankPosition,
        ScoreBreakdown breakdown
) {

    public ScoredRecommendation {
        Objects.requireNonNull(song, "song must not be null");
        Objects.requireNonNull(matchReason, "matchReason must not be null");
        if (rankPosition < 1) {
            throw new IllegalArgumentException("rankPosition must be >= 1: " + rankPosition);
        }
        // breakdown은 readById 경로에서 null. 응답 DTO에서 분기 처리.
    }

    /**
     * breakdown 없는 호출(과거 추천 재조회 등) 편의 생성자.
     */
    public ScoredRecommendation(final Song song, final double score, final String matchReason, final int rankPosition) {
        this(song, score, matchReason, rankPosition, null);
    }
}
