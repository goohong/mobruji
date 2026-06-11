package com.mobruji.recommendation.domain;

import java.util.List;
import java.util.Objects;

/**
 * 추천 요청 1건의 최종 결과(요청 ID + 정렬·다양성 후처리 마친 추천 곡 리스트). application 계층이
 * {@code api.dto}에 의존하지 않도록 domain 레이어에 두는 결과 컨테이너 (ADR 0005 §A-7).
 */
public record RecommendationResult(
        Long requestId,
        List<ScoredRecommendation> recommendations
) {

    public RecommendationResult {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(recommendations, "recommendations must not be null");
        recommendations = List.copyOf(recommendations);
    }
}
