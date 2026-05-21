package com.mobruji.feedback.api.dto;

import java.time.LocalDateTime;

import com.mobruji.feedback.domain.Like;

public record LikeResponse(
        Long id,
        String sessionId,
        Long songId,
        LocalDateTime createdAt
) {

    public static LikeResponse from(final Like like) {
        return new LikeResponse(like.getId(), like.getSessionId(), like.getSongId(), like.getCreatedAt());
    }
}
