package com.mobruji.recommendation.api.dto;

import java.util.List;

import com.mobruji.recommendation.domain.DuetRecommendationResult;
import com.mobruji.recommendation.domain.FilterRelaxation;

/**
 * 듀엣·함께 부르기형(P-G) 추천 응답 DTO.
 *
 * <p>{@code persona}는 페르소나 식별자("P-G", 설명 가능성 — persona-expansion-social-emotional.md §4). {@code requestId}는
 * 단일 추천과 같은 경로로 영속된 추천 요청 ID 라 fe 는 곡 피드백·재조회를 단일 추천과 같은 경로로 처리할 수 있다.
 * {@code relaxed}/{@code relaxedFilters}는 0건 fallback(#1668) 완화 신호다.
 *
 * <p>{@code recommendations}는 듀엣 강편향(MIXED 듀엣곡 우위 + 두 음역 동시 충족)으로 재정렬한 곡 묶음이다. 각 항목은 단일 추천과
 * 같은 곡 형상({@link RecommendedSongResponse}, 결과 카드 렌더 재사용) + 페르소나별 "파트 분담" 안내({@code partAssignmentReason})를
 * 함께 노출한다.
 */
public record DuetRecommendationResponse(
        String persona,
        Long requestId,
        boolean relaxed,
        List<String> relaxedFilters,
        List<DuetSongResponse> recommendations
) {

    public static DuetRecommendationResponse from(final DuetRecommendationResult result) {
        final List<DuetSongResponse> duetSongResponses = result.recommendations().stream()
                .map(DuetSongResponse::from)
                .toList();
        final List<String> relaxedFilters = result.relaxedFilters().stream()
                .map(FilterRelaxation::name)
                .toList();
        return new DuetRecommendationResponse(
                result.persona().code(), result.requestId(), result.relaxed(), relaxedFilters, duetSongResponses);
    }

    /**
     * 듀엣 추천 1건의 응답 — 단일 추천과 같은 곡 형상 + "파트 분담" 안내.
     */
    public record DuetSongResponse(
            String partAssignmentReason,
            RecommendedSongResponse recommendation
    ) {

        public static DuetSongResponse from(
                final DuetRecommendationResult.DuetRecommendation duetRecommendation) {
            return new DuetSongResponse(
                    duetRecommendation.partAssignmentReason(),
                    RecommendedSongResponse.from(duetRecommendation.recommendation()));
        }
    }
}
