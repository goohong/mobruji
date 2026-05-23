package com.mobruji.feedback.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import com.mobruji.feedback.domain.Bookmark;
import com.mobruji.feedback.infrastructure.BookmarkRepository;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * {@link BookmarkService#readPageBySessionId(String, int, int)} 분기 회귀 가드. {@link LikeServicePageReadTest} 와 동일 패턴의
 * empty/정상 2분기만 확인 (Like 측에서 누락 곡 / 단순 위임은 이미 cover).
 */
@ExtendWith(MockitoExtension.class)
class BookmarkServicePageReadTest {

    private static final String SESSION_ID = "session-bookmark-page";

    @Mock
    private BookmarkRepository bookmarkRepository;

    @Mock
    private SongRepository songRepository;

    private SimpleMeterRegistry meterRegistry;
    private BookmarkService bookmarkService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        bookmarkService = new BookmarkService(bookmarkRepository, songRepository, meterRegistry);
    }

    @Test
    @DisplayName("readPageBySessionId: totalCount=0 → empty slice + Pageable 조회/songRepository 미호출")
    void readPageBySessionId_emptyTotal_returnsEmptySliceWithoutPageQuery() {
        // given
        given(bookmarkRepository.countBySessionId(SESSION_ID)).willReturn(0L);

        // when
        final BookmarkService.BookmarkPageSlice slice = bookmarkService.readPageBySessionId(SESSION_ID, 0, 20);

        // then
        assertThat(slice.bookmarks()).isEmpty();
        assertThat(slice.songsById()).isEmpty();
        assertThat(slice.totalCount()).isZero();
        verify(bookmarkRepository, never()).findBySessionIdOrderByCreatedAtDesc(any(), any(Pageable.class));
        verify(songRepository, never()).findAllById(any());
    }

    @Test
    @DisplayName("readPageBySessionId: 정상 — bookmarks + songsById(by id) + totalCount 합성")
    void readPageBySessionId_normal_composesBookmarksAndSongs() {
        // given
        final Bookmark firstBookmark = Bookmark.create(SESSION_ID, 11L);
        final Bookmark secondBookmark = Bookmark.create(SESSION_ID, 22L);
        final Song firstSong = mockSong(11L);
        final Song secondSong = mockSong(22L);
        given(bookmarkRepository.countBySessionId(SESSION_ID)).willReturn(2L);
        given(bookmarkRepository.findBySessionIdOrderByCreatedAtDesc(eq(SESSION_ID), any(Pageable.class)))
                .willReturn(List.of(firstBookmark, secondBookmark));
        given(songRepository.findAllById(List.of(11L, 22L))).willReturn(List.of(firstSong, secondSong));

        // when
        final BookmarkService.BookmarkPageSlice slice = bookmarkService.readPageBySessionId(SESSION_ID, 0, 10);

        // then
        assertThat(slice.bookmarks()).containsExactly(firstBookmark, secondBookmark);
        assertThat(slice.songsById()).containsOnlyKeys(11L, 22L);
        assertThat(slice.songsById().get(11L)).isSameAs(firstSong);
        assertThat(slice.songsById().get(22L)).isSameAs(secondSong);
        assertThat(slice.totalCount()).isEqualTo(2L);
    }

    private static Song mockSong(final Long id) {
        final Song song = mock(Song.class);
        given(song.getId()).willReturn(id);
        return song;
    }
}
