package com.mobruji.feedback.api.dto;

import java.time.LocalDateTime;

import com.mobruji.feedback.domain.Bookmark;

public record BookmarkResponse(
        Long id,
        String sessionId,
        Long songId,
        LocalDateTime createdAt
) {

    public static BookmarkResponse from(final Bookmark bookmark) {
        return new BookmarkResponse(
                bookmark.getId(), bookmark.getSessionId(), bookmark.getSongId(), bookmark.getCreatedAt());
    }
}
