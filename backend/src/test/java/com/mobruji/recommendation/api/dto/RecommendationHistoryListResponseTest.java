package com.mobruji.recommendation.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecommendationHistoryListResponseTest {

    @Test
    @DisplayName("빈 리스트도 wrapper 로 감쌀 수 있다 (fe 가 키 명을 기대하므로 null 이 아닌 빈 리스트로 노출)")
    void emptyList_wrappedSafely() {
        final RecommendationHistoryListResponse recommendationHistoryListResponse = new RecommendationHistoryListResponse(
                List.of());

        assertThat(recommendationHistoryListResponse.recommendationHistoryResponses()).isEmpty();
    }

    @Test
    @DisplayName("입력 순서가 그대로 보존된다 (spec §5-2 — 최신순 정렬은 호출 측 책임이며 wrapper 는 순서를 바꾸지 않는다)")
    void preservesInputOrder() {
        final RecommendationHistoryResponse first = new RecommendationHistoryResponse(
                10L, 48, 72, "UPBEAT", 120, LocalDateTime.now(), List.of());
        final RecommendationHistoryResponse second = new RecommendationHistoryResponse(
                9L, 50, 80, "CALM", null, LocalDateTime.now().minusMinutes(1), List.of());
        final RecommendationHistoryResponse third = new RecommendationHistoryResponse(
                8L, 52, 76, null, 100, LocalDateTime.now().minusMinutes(2), List.of());

        final RecommendationHistoryListResponse recommendationHistoryListResponse = new RecommendationHistoryListResponse(
                List.of(first, second, third));

        assertThat(recommendationHistoryListResponse.recommendationHistoryResponses())
                .extracting(RecommendationHistoryResponse::requestId)
                .containsExactly(10L, 9L, 8L);
    }

    @Test
    @DisplayName("record equals/hashCode: 내용이 같으면 동일한 wrapper 로 간주된다")
    void recordEquality() {
        final RecommendationHistoryResponse single = new RecommendationHistoryResponse(
                1L, 48, 72, "UPBEAT", 120, LocalDateTime.of(2026, 1, 1, 0, 0), List.of());

        final RecommendationHistoryListResponse a = new RecommendationHistoryListResponse(List.of(single));
        final RecommendationHistoryListResponse b = new RecommendationHistoryListResponse(List.of(single));

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
