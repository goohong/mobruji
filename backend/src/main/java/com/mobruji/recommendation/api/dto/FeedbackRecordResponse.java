package com.mobruji.recommendation.api.dto;

import com.mobruji.recommendation.domain.FeedbackReaction;
import com.mobruji.recommendation.domain.SessionFeedback;

/**
 * 스와이프 반응 기록(upsert) 응답(#1545 §5-2). 저장된 최신 상태({@code songId, reaction})를 회신한다.
 */
public record FeedbackRecordResponse(
        Long songId,
        FeedbackReaction reaction
) {

    public static FeedbackRecordResponse from(final SessionFeedback sessionFeedback) {
        return new FeedbackRecordResponse(sessionFeedback.getSongId(), sessionFeedback.getReaction());
    }
}
