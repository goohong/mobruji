package com.mobruji.feedback.api.dto;

import java.time.LocalDateTime;

import com.mobruji.feedback.domain.Bookmark;
import com.mobruji.song.api.dto.SongResponse;
import com.mobruji.song.domain.Song;

/**
 * 북마크 단건 + 해당 곡 메타데이터 결합 응답.
 *
 * <p>spec {@code recommendation-history-and-feedback.md §5-2} — {@link LikeWithSongResponse} 와 동일 패턴(곡
 * join, N+1 회피).
 */
public record BookmarkWithSongResponse(
        Long id,
        SongResponse song,
        LocalDateTime bookmarkedAt
) {

    public static BookmarkWithSongResponse from(final Bookmark bookmark, final Song song) {
        return new BookmarkWithSongResponse(
                bookmark.getId(), SongResponse.from(song), bookmark.getCreatedAt());
    }
}
