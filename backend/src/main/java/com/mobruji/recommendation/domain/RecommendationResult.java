package com.mobruji.recommendation.domain;

import java.util.List;
import java.util.Objects;

/**
 * 추천 요청 1건의 최종 결과(요청 ID + 정렬·다양성 후처리 마친 추천 곡 리스트). application 계층이
 * {@code api.dto}에 의존하지 않도록 domain 레이어에 두는 결과 컨테이너 (ADR 0005 §A-7).
 *
 * <p>{@code relaxedFilters}(#1668)는 결과 0건(빈 화면)을 막기 위해 단계적으로 완화한 필터 목록이다. 비어 있으면
 * 정상 매칭({@link #relaxed()} == {@code false}), 비어 있지 않으면 제외 필터를 완화해 채운 결과임을 뜻한다.
 * FE 가 "정확히 맞는 곡이 부족해 가까운 곡을 보여드려요" 안내를 띄우는 신호 — {@link FilterRelaxation} 참고.
 */
public record RecommendationResult(
        Long requestId,
        List<ScoredRecommendation> recommendations,
        List<FilterRelaxation> relaxedFilters
) {

    public RecommendationResult {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(recommendations, "recommendations must not be null");
        Objects.requireNonNull(relaxedFilters, "relaxedFilters must not be null");
        recommendations = List.copyOf(recommendations);
        relaxedFilters = List.copyOf(relaxedFilters);
    }

    /**
     * 완화 없이 정상 매칭된 결과용 편의 생성자. 재조회(history/단건)·정상 create 경로가 그대로 쓴다.
     */
    public RecommendationResult(final Long requestId, final List<ScoredRecommendation> recommendations) {
        this(requestId, recommendations, List.of());
    }

    /**
     * 필터 완화가 한 단계라도 적용됐는지 — 응답 {@code relaxed} 플래그(#1668).
     */
    public boolean relaxed() {
        return !relaxedFilters.isEmpty();
    }
}
