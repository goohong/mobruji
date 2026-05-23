package com.mobruji.feedback.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.feedback.domain.Bookmark;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;

/**
 * {@link BookmarkWithSongResponse#from(Bookmark, Song)} factory 회귀 가드.
 *
 * <p>{@link LikeWithSongResponseTest} 와 동일 패턴 — bookmark + song meta + bookmarkedAt(createdAt) 매핑.
 */
class BookmarkWithSongResponseTest {

    @Test
    @DisplayName("from: Bookmark + Song 결합 — song 메타 + bookmarkedAt(createdAt) 모두 매핑")
    void from_combinesBookmarkAndSongMeta() {
        // given
        final Song song = Song.builder()
                .title("벚꽃 엔딩").artist("버스커 버스커")
                .keyOriginal(MusicalKey.A_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(60).highMidi(76)
                .build();
        final Bookmark bookmark = Bookmark.create("session-a", 100L);

        // when
        final BookmarkWithSongResponse bookmarkWithSongResponse = BookmarkWithSongResponse.from(bookmark, song);

        // then
        assertThat(bookmarkWithSongResponse.song()).isNotNull();
        assertThat(bookmarkWithSongResponse.song().title()).isEqualTo("벚꽃 엔딩");
        assertThat(bookmarkWithSongResponse.song().artist()).isEqualTo("버스커 버스커");
        assertThat(bookmarkWithSongResponse.song().lowMidi()).isEqualTo(60);
        assertThat(bookmarkWithSongResponse.song().highMidi()).isEqualTo(76);
        assertThat(bookmarkWithSongResponse.bookmarkedAt()).isEqualTo(bookmark.getCreatedAt());
    }

    @Test
    @DisplayName("from: Song 메타 누락(lowMidi/highMidi null) — song.noteName 도 null 로 전달")
    void from_whenSongMetaPartiallyNull_propagatesNulls() {
        // given
        final Song song = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
        final Bookmark bookmark = Bookmark.create("session-b", 1L);

        // when
        final BookmarkWithSongResponse bookmarkWithSongResponse = BookmarkWithSongResponse.from(bookmark, song);

        // then
        assertThat(bookmarkWithSongResponse.song().lowMidi()).isNull();
        assertThat(bookmarkWithSongResponse.song().highMidi()).isNull();
        assertThat(bookmarkWithSongResponse.song().lowestNoteName()).isNull();
        assertThat(bookmarkWithSongResponse.song().highestNoteName()).isNull();
        assertThat(bookmarkWithSongResponse.bookmarkedAt()).isEqualTo(bookmark.getCreatedAt());
    }
}
