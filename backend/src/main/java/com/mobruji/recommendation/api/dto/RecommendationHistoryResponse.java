package com.mobruji.recommendation.api.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.mobruji.recommendation.domain.RecommendationResult;
import com.mobruji.recommendation.domain.RecommendationRequestEntity;

/**
 * 세션별 추천 히스토리 1건의 응답 형태 (요청 메타데이터 + 결과 곡 리스트).
 *
 * <p>spec: docs/features/recommendation-history-and-feedback.md §5-2 — GET
 * {@code /api/v1/sessions/{sessionId}/recommendation-history} 응답의 단일 entry.
 *
 * <p>{@link #recommendations} 의 각 항목은 영속된 {@code Recommendation}(=spec 용어
 * {@code RecommendationResultEntry}) 1건과 1:1 대응되며, breakdown 은 영속하지 않으므로 항상 null 로
 * 노출된다 (fe 는 null 일 경우 펼침 영역을 숨긴다).
 */
public record RecommendationHistoryResponse(
        Long requestId,
        String sessionId,
        Integer voiceRangeLow,
        Integer voiceRangeHigh,
        String mood,
        Integer preferredBpm,
        LocalDateTime requestedAt,
        List<RecommendedSongResponse> recommendations
) {

    public static RecommendationHistoryResponse from(
            final RecommendationRequestEntity recommendationRequestEntity,
            final RecommendationResult recommendationResult) {
        final List<RecommendedSongResponse> recommendedSongResponses = recommendationResult.recommendations().stream()
                .map(RecommendedSongResponse::from)
                .toList();
        return new RecommendationHistoryResponse(
                recommendationRequestEntity.getId(),
                recommendationRequestEntity.getSessionId(),
                recommendationRequestEntity.getVoiceRangeLow(),
                recommendationRequestEntity.getVoiceRangeHigh(),
                recommendationRequestEntity.getMood() == null ? null : recommendationRequestEntity.getMood().name(),
                recommendationRequestEntity.getPreferredBpm(),
                recommendationRequestEntity.getCreatedAt(),
                recommendedSongResponses);
    }
}
