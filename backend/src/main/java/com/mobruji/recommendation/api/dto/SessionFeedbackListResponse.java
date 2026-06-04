package com.mobruji.recommendation.api.dto;

import java.util.List;

/**
 * 세션 반응 목록 응답 wrapper(#1545 §5-2). Spring Data {@code Page<>} 직접 노출은 직렬화 안정성 위해 피하고
 * {@code responses}/page/size/totalCount/hasNext 평면 구조로 회신한다({@code LikeListResponse} 와 동일 패턴).
 */
public record SessionFeedbackListResponse(
        List<SessionFeedbackResponse> responses,
        int page,
        int size,
        long totalCount,
        boolean hasNext
) {
}
