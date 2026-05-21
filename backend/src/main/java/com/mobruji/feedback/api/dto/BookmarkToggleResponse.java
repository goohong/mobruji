package com.mobruji.feedback.api.dto;

import com.mobruji.feedback.application.ToggleResult;

/**
 * Bookmark toggle 응답. bookmarked=true면 새로 북마크가 생성됐고, false면 기존 북마크가 삭제됐다.
 */
public record BookmarkToggleResponse(
        boolean bookmarked,
        Long songId
) {

    public static BookmarkToggleResponse from(final ToggleResult toggleResult) {
        return new BookmarkToggleResponse(toggleResult.active(), toggleResult.songId());
    }
}
