package com.mobruji.recommendation.api.dto;

import com.mobruji.recommendation.domain.ScoreBreakdown;

/**
 * 추천 점수 신호 분해 응답 DTO. fe 14 matchReason 펼침 UX에서 사용자가 추천 사유를 raw 신호 수준까지 확인할 수 있도록
 * 노출한다. 모든 필드는 가중치 적용 전 raw 값(0~1).
 *
 * <p>v2(#218)에서 {@code tempoMatch}, #1487에서 {@code generationFit} 필드가 추가되어 7개 신호로 확장됐다.
 *
 * <p>{@link #from(ScoreBreakdown)} 정적 팩터리만으로 변환한다 — domain → api.dto 방향 단방향(레이어 룰 준수).
 * 영속 엔티티에는 저장되지 않으므로 {@code null} 입력(과거 추천 재조회 경로)은 {@code null} 반환.
 */
public record ScoreBreakdownResponse(
        double keyMatch,
        double rangeFit,
        double genreMatch,
        double moodMatch,
        double popularity,
        double tempoMatch,
        double generationFit
) {

    public static ScoreBreakdownResponse from(final ScoreBreakdown scoreBreakdown) {
        if (scoreBreakdown == null) {
            return null;
        }
        return new ScoreBreakdownResponse(
                scoreBreakdown.keyMatch(),
                scoreBreakdown.rangeFit(),
                scoreBreakdown.genreMatch(),
                scoreBreakdown.moodMatch(),
                scoreBreakdown.popularity(),
                scoreBreakdown.tempoMatch(),
                scoreBreakdown.generationFit());
    }
}
