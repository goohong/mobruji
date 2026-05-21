package com.mobruji.recommendation.api.dto;

import java.util.List;

/**
 * 세션별 추천 히스토리 응답 wrapper.
 *
 * <p>spec: docs/features/recommendation-history-and-feedback.md §5-2 — 향후 페이지네이션 / 메타(총건수 등)를
 * 추가할 수 있도록 배열을 직접 노출하지 않고 객체로 감싼다. fe 와 키 명 변경 없이 진화 가능.
 *
 * <p>{@code recommendationHistoryResponses} 는 최신순(createdAt DESC) 정렬을 따른다.
 */
public record RecommendationHistoryListResponse(List<RecommendationHistoryResponse> recommendationHistoryResponses) {
}
