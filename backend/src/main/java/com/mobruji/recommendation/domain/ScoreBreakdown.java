package com.mobruji.recommendation.domain;

/**
 * 추천 점수 신호 분해 — Spotify "Why this song?" UX 영감(P2). 사용자가 추천 이유를 펼쳐볼 때 각 신호의 raw 값(0~1)을
 * 그대로 노출해 설명 가능성을 확보한다.
 *
 * <p>5개 신호는 v1 점수식의 입력값과 1:1 매칭된다 (가중치 적용 전 raw):
 *
 * <ul>
 * <li>{@code keyMatch} — 곡 키가 알려져 있는지 여부(UNKNOWN=0.5 중립, 그 외 1.0). 사용자에게 "키 정보를 안다/모른다"의
 * 불확실성을 드러내는 메타 신호.</li>
 * <li>{@code rangeFit} — 곡 키 추정 보컬 음역과 사용자 음역의 overlap 비율 (= 기존 voiceRangeFit).</li>
 * <li>{@code genreMatch} — v1에서는 입력 필드 없음 → 항상 0. 가중치만 보존.</li>
 * <li>{@code moodMatch} — 분위기 일치 시 1.0 / 미입력·불일치 0.0.</li>
 * <li>{@code popularity} — v1에서는 시드 데이터에 popularity 컬럼 없음 → 1.0 고정.</li>
 * </ul>
 *
 * <p>가중 합산된 최종 score는 별도 필드({@link ScoredRecommendation#score()})로 보존한다. 본 record는 사후 분석/설명용
 * raw 신호만 담는다. 점수 산식 자체는 변경하지 않으므로 결정성·기존 회귀 가드에 영향 없음.
 *
 * @see ScoredRecommendation
 */
public record ScoreBreakdown(
        double keyMatch,
        double rangeFit,
        double genreMatch,
        double moodMatch,
        double popularity
) {

    public ScoreBreakdown {
        requireUnitInterval("keyMatch", keyMatch);
        requireUnitInterval("rangeFit", rangeFit);
        requireUnitInterval("genreMatch", genreMatch);
        requireUnitInterval("moodMatch", moodMatch);
        requireUnitInterval("popularity", popularity);
    }

    private static void requireUnitInterval(final String name, final double value) {
        if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be in [0.0, 1.0]: " + value);
        }
    }
}
