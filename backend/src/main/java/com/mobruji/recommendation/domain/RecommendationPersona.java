package com.mobruji.recommendation.domain;

/**
 * 제품 북극성 페르소나 식별자 — 추천 의도별 사용자 군. 영속 엔티티가 아니라 추천 의도·랭킹 가중 프리셋의 분류 라벨이다.
 *
 * <p>기존 3종(개인·실용 축): {@code P_A} 연습형 / {@code P_B} 부른곡 기반 / {@code P_C} 즉석 분위기·나이대.
 * 사회·감정 축 4종(#1591, 채택 2026-06-03): {@code P_D} 모임 사회자형 / {@code P_E} 안전곡형 / {@code P_F} 과시·킬링파트형 /
 * {@code P_G} 듀엣·함께 부르기형.
 *
 * <p>정의 SoT: {@code docs/roadmap/overnight-2026-06-02.md}(P-A~C) +
 * {@code docs/features/persona-expansion-social-emotional.md} §2(P-D~G).
 * 도메인 용어 등재: {@code docs/ai-harness/06-domain-model.md} §4-1.
 *
 * <p>{@link #code()} 는 응답·로그에 노출하는 사용자 식별자 표기("P-D" 등) — enum 상수명(P_D)과 달리 하이픈 표기를 외부 계약으로 둔다.
 */
public enum RecommendationPersona {

    P_A("P-A"),
    P_B("P-B"),
    P_C("P-C"),
    P_D("P-D"),
    P_E("P-E"),
    P_F("P-F"),
    P_G("P-G");

    private final String code;

    RecommendationPersona(final String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
