package com.mobruji.recommendation.domain;

/**
 * 스와이프 세션 반응 종류(#1545 recommendation-feedback-loop.md §5-1).
 *
 * <p>{@code LIKE} 는 선호 신호(부른곡 시드와 함께 선호 집합을 구성), {@code PASS} 는 회피 신호(추천 후보에서 제외)를 뜻한다.
 */
public enum FeedbackReaction {
    LIKE,
    PASS
}
