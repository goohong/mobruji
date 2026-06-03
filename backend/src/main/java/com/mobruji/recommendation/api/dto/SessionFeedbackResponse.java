package com.mobruji.recommendation.api.dto;

import java.time.LocalDateTime;

import com.mobruji.recommendation.domain.FeedbackReaction;
import com.mobruji.recommendation.domain.SessionFeedback;

/**
 * 세션 반응 목록 단건 응답(#1545 §5-2). 곡 메타 없이 반응 그 자체만 — fe 는 보유 카드 메타로 렌더한다.
 */
public record SessionFeedbackResponse(
        Long id,
        Long songId,
        FeedbackReaction reaction,
        LocalDateTime reactedAt
) {

    public static SessionFeedbackResponse from(final SessionFeedback sessionFeedback) {
        return new SessionFeedbackResponse(
                sessionFeedback.getId(),
                sessionFeedback.getSongId(),
                sessionFeedback.getReaction(),
                sessionFeedback.getCreatedAt());
    }
}
