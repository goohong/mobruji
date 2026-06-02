package com.mobruji.recommendation.domain;

/**
 * 추천 요청자의 연령대(선택 입력). generationFit 신호의 입력으로, 해당 세대의 대표/인기곡이 많은 시기(곡 발매연도)에
 * 가중을 준다 (#1487). 미입력(null) 이면 generationFit=0 으로 처리되어 랭킹에 영향이 없다(하위호환).
 *
 * <p>세대별 대표 시기는 코드에 박지 않고 {@code RecommendationProperties.Generation.representativeYear} 로 외부화한다 —
 * "지금 기준 formative 시기" 추정값이라 운영 측정 후 yml 만 바꿔 튜닝할 수 있게 한다.
 */
public enum AgeGroup {

    TEENS,
    TWENTIES,
    THIRTIES,
    FORTIES,
    FIFTIES,
    SIXTIES_PLUS,
}
