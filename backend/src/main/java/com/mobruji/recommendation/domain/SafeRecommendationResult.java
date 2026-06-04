package com.mobruji.recommendation.domain;

import java.util.List;
import java.util.Objects;

import com.mobruji.song.domain.Difficulty;

/**
 * 안전곡형(P-E) 추천 1건의 최종 결과 — 페르소나 식별자 + 영속 요청 ID + 안전곡 강편향으로 재정렬한 추천 곡 묶음.
 * application 계층이 {@code api.dto}에 의존하지 않도록 domain 레이어에 두는 결과 컨테이너 (ADR 0005 §A-7).
 *
 * <p>안전곡 프리셋(persona-expansion-social-emotional.md §2/§5)은 기존 추천 신호의 *가중 프리셋 재조합* 으로 표현한다 —
 * 신규 점수 함수·가중치 변경 없이 ① 느린 템포·잔잔 분위기 입력({@code CALM})으로 {@code tempoMatch}/{@code moodMatch} 를
 * 안전 쪽으로 편향하고 ② 산출된 결과를 {@code difficulty=EASY} 우위로 결정적으로 재정렬한다. 같은 입력이면 같은 결과(결정성 보존).
 *
 * <p>{@code requestId} 는 단일 추천과 같은 경로로 영속된 추천 요청 ID 다 — fe(#1600)는 곡 피드백·재조회를 단일 추천과 같은
 * 경로로 처리할 수 있다. {@code relaxedFilters} 는 0건 fallback(#1668) 으로 완화한 필터 목록(빈 화면 방지).
 */
public record SafeRecommendationResult(
        RecommendationPersona persona,
        Long requestId,
        List<SafeRecommendation> recommendations,
        List<FilterRelaxation> relaxedFilters
) {

    public SafeRecommendationResult {
        Objects.requireNonNull(persona, "persona must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(recommendations, "recommendations must not be null");
        Objects.requireNonNull(relaxedFilters, "relaxedFilters must not be null");
        recommendations = List.copyOf(recommendations);
        relaxedFilters = List.copyOf(relaxedFilters);
    }

    /**
     * 필터 완화가 한 단계라도 적용됐는지 — 응답 {@code relaxed} 플래그(#1668).
     */
    public boolean relaxed() {
        return !relaxedFilters.isEmpty();
    }

    /**
     * 안전곡 추천 1건 — 단일 추천과 같은 곡 형상({@link ScoredRecommendation}) + 페르소나별 "안심 포인트" 사유.
     * {@code safetyReason} 은 곡 난이도({@code EASY})·음역 적합도에서 결정적으로 파생한 "쉬운 이유" 한 줄이다
     * (persona-expansion-social-emotional.md §4 — 설명 가능성).
     */
    public record SafeRecommendation(
            ScoredRecommendation recommendation,
            String safetyReason
    ) {

        public SafeRecommendation {
            Objects.requireNonNull(recommendation, "recommendation must not be null");
            Objects.requireNonNull(safetyReason, "safetyReason must not be null");
        }

        /**
         * 추천 곡에 "안심 포인트"(쉬운 이유) 한 줄을 결정적으로 붙인다. 곡 난이도({@link Difficulty#EASY})와 음역 적합도
         * ({@link ScoredRecommendation#voiceFit()})를 결합해 사유를 고른다. 난이도 미상·음역 정보 부재 곡도 빈 응답 대신
         * graceful 한 기본 사유를 받아 P-E 가 항상 "안심 포인트"를 노출한다.
         */
        public static SafeRecommendation of(final ScoredRecommendation recommendation) {
            return new SafeRecommendation(recommendation, safetyReasonOf(recommendation));
        }

        private static String safetyReasonOf(final ScoredRecommendation recommendation) {
            final Difficulty difficulty = recommendation.song().getDifficulty();
            final Double voiceFit = recommendation.voiceFit();
            final boolean comfortableRange = voiceFit != null && voiceFit >= 0.4;
            if (difficulty == Difficulty.EASY && comfortableRange) {
                return "쉬운 난이도에 음역대도 여유 있어 안심하고 부를 수 있어요";
            }
            if (difficulty == Difficulty.EASY) {
                return "쉬운 난이도라 부담 없이 부를 수 있어요";
            }
            if (comfortableRange) {
                return "음역대에 여유 있게 맞아 무리 없이 부를 수 있어요";
            }
            return "익숙하게 따라 부르기 좋은 곡이에요";
        }
    }
}
