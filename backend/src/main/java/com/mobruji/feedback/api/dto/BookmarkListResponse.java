package com.mobruji.feedback.api.dto;

import java.util.List;

/**
 * 북마크 리스트 페이지네이션 wrapper. {@link LikeListResponse} 와 동일 패턴.
 */
public record BookmarkListResponse(
        List<BookmarkWithSongResponse> responses,
        int page,
        int size,
        long totalCount,
        boolean hasNext
) {
}
