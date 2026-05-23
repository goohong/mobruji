package com.mobruji.feedback.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.feedback.domain.Bookmark;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;

/**
 * {@link BookmarkListResponse} wrapper 회귀 가드.
 *
 * <p>{@link LikeListResponseTest} 와 동일 패턴 — wrapper 가 페이지네이션 의미를 보존하는지 잠근다.
 */
class BookmarkListResponseTest {

    @Test
    @DisplayName("정상 구성: responses + page/size/totalCount/hasNext 매핑 그대로 보존")
    void preservesAllFields() {
        // given
        final BookmarkWithSongResponse first = sampleBookmarkWithSongResponse(10L, "벚꽃 엔딩");
        final BookmarkWithSongResponse second = sampleBookmarkWithSongResponse(11L, "취중진담");

        // when
        final BookmarkListResponse bookmarkListResponse = new BookmarkListResponse(
                List.of(first, second), 0, 20, 2L, false);

        // then
        assertThat(bookmarkListResponse.responses()).hasSize(2);
        assertThat(bookmarkListResponse.responses()).containsExactly(first, second);
        assertThat(bookmarkListResponse.page()).isEqualTo(0);
        assertThat(bookmarkListResponse.size()).isEqualTo(20);
        assertThat(bookmarkListResponse.totalCount()).isEqualTo(2L);
        assertThat(bookmarkListResponse.hasNext()).isFalse();
    }

    @Test
    @DisplayName("빈 리스트도 wrapper 로 감쌀 수 있다 (fe 가 키 명을 기대하므로 null 이 아닌 빈 리스트로 노출)")
    void emptyList_wrappedSafely() {
        // given / when
        final BookmarkListResponse bookmarkListResponse = new BookmarkListResponse(
                List.of(), 0, 20, 0L, false);

        // then
        assertThat(bookmarkListResponse.responses()).isEmpty();
        assertThat(bookmarkListResponse.totalCount()).isZero();
        assertThat(bookmarkListResponse.hasNext()).isFalse();
    }

    @Test
    @DisplayName("hasNext=true: 다음 페이지가 존재하는 경우 그대로 노출 (계산은 controller 책임)")
    void hasNextTrue_preserved() {
        // given
        final BookmarkWithSongResponse only = sampleBookmarkWithSongResponse(1L, "t");

        // when
        final BookmarkListResponse bookmarkListResponse = new BookmarkListResponse(
                List.of(only), 0, 1, 5L, true);

        // then
        assertThat(bookmarkListResponse.hasNext()).isTrue();
        assertThat(bookmarkListResponse.totalCount()).isEqualTo(5L);
    }

    @Test
    @DisplayName("record equals/hashCode: 내용이 같으면 동일한 wrapper 로 간주")
    void recordEquality() {
        // given
        final BookmarkWithSongResponse single = sampleBookmarkWithSongResponse(1L, "t");

        // when
        final BookmarkListResponse a = new BookmarkListResponse(List.of(single), 0, 20, 1L, false);
        final BookmarkListResponse b = new BookmarkListResponse(List.of(single), 0, 20, 1L, false);

        // then
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    private BookmarkWithSongResponse sampleBookmarkWithSongResponse(final Long bookmarkId, final String title) {
        final Song song = Song.builder()
                .title(title).artist("artist")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
        final Bookmark bookmark = Bookmark.create("session", 1L);
        final BookmarkWithSongResponse response = BookmarkWithSongResponse.from(bookmark, song);
        return new BookmarkWithSongResponse(bookmarkId, response.song(),
                response.bookmarkedAt() != null ? response.bookmarkedAt() : LocalDateTime.now());
    }
}
