package com.mobruji.recommendation.api.dto;

import java.util.List;

import com.mobruji.recommendation.domain.FilterRelaxation;
import com.mobruji.recommendation.domain.ShowoffRecommendationResult;

/**
 * 과시·킬링파트형(P-F) 추천 응답 DTO.
 *
 * <p>{@code persona}는 페르소나 식별자("P-F", 설명 가능성 — persona-expansion-social-emotional.md §4). {@code requestId}는
 * 단일 추천과 같은 경로로 영속된 추천 요청 ID 라 fe 는 곡 피드백·재조회를 단일 추천과 같은 경로로 처리할 수 있다.
 * {@code relaxed}/{@code relaxedFilters}는 0건 fallback(#1668) 완화 신호다.
 *
 * <p>{@code recommendations}는 과시 강편향(음역 천장 근접 + HARD 우위)으로 재정렬한 곡 묶음이다. 각 항목은 단일 추천과 같은 곡 형상
 * ({@link RecommendedSongResponse}, 결과 카드 렌더 재사용) + 페르소나별 "킬링파트 안내"({@code killingPartReason})를 함께 노출한다.
 */
public record ShowoffRecommendationResponse(
        String persona,
        Long requestId,
        boolean relaxed,
        List<String> relaxedFilters,
        List<ShowoffSongResponse> recommendations
) {

    public static ShowoffRecommendationResponse from(final ShowoffRecommendationResult result) {
        final List<ShowoffSongResponse> showoffSongResponses = result.recommendations().stream()
                .map(ShowoffSongResponse::from)
                .toList();
        final List<String> relaxedFilters = result.relaxedFilters().stream()
                .map(FilterRelaxation::name)
                .toList();
        return new ShowoffRecommendationResponse(
                result.persona().code(), result.requestId(), result.relaxed(), relaxedFilters, showoffSongResponses);
    }

    /**
     * 과시 추천 1건의 응답 — 단일 추천과 같은 곡 형상 + "킬링파트 안내" 사유.
     */
    public record ShowoffSongResponse(
            String killingPartReason,
            RecommendedSongResponse recommendation
    ) {

        public static ShowoffSongResponse from(
                final ShowoffRecommendationResult.ShowoffRecommendation showoffRecommendation) {
            return new ShowoffSongResponse(
                    showoffRecommendation.killingPartReason(),
                    RecommendedSongResponse.from(showoffRecommendation.recommendation()));
        }
    }
}
