package com.mobruji.feedback.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Bookmark#create(String, Long)} invariant 가드.
 *
 * <p>spec recommendation-history-and-feedback.md §3 비기능 — sessionId/songId null 금지.
 * {@code 08-code-conventions.md} — Domain 매개변수 전수 {@link java.util.Objects#requireNonNull} 강제.
 */
class BookmarkTest {

    private static final String SESSION_ID = "session-bookmark-invariant";
    private static final Long SONG_ID = 200L;

    @Test
    @DisplayName("create: 정상 sessionId+songId → 비-null instance + 필드 매핑 + createdAt 즉시 세팅")
    void create_withValidArgs_returnsInstance() {
        // given
        final String sessionId = SESSION_ID;
        final Long songId = SONG_ID;

        // when
        final Bookmark bookmark = Bookmark.create(sessionId, songId);

        // then
        assertThat(bookmark).isNotNull();
        assertThat(bookmark.getSessionId()).isEqualTo(sessionId);
        assertThat(bookmark.getSongId()).isEqualTo(songId);
        assertThat(bookmark.getCreatedAt()).isNotNull();
        assertThat(bookmark.getId()).isNull();
    }

    @Test
    @DisplayName("create: sessionId null → NullPointerException(message 에 sessionId 포함)")
    void create_withNullSessionId_throws() {
        assertThatThrownBy(() -> Bookmark.create(null, SONG_ID))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sessionId");
    }

    @Test
    @DisplayName("create: songId null → NullPointerException(message 에 songId 포함)")
    void create_withNullSongId_throws() {
        assertThatThrownBy(() -> Bookmark.create(SESSION_ID, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("songId");
    }
}
