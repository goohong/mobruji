package com.mobruji.feedback.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mobruji.feedback.domain.Bookmark;
import com.mobruji.feedback.infrastructure.BookmarkRepository;
import com.mobruji.song.domain.SongNotFoundException;
import com.mobruji.song.infrastructure.SongRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * BookmarkService#toggle 분기 회귀 가드. LikeService 와 동일 4개 시나리오 mirror.
 */
@ExtendWith(MockitoExtension.class)
class BookmarkServiceTest {

    private static final String SESSION_ID = "session-xyz";
    private static final Long SONG_ID = 77L;

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
    @DisplayName("toggle: 미존재 songId면 SongNotFoundException + save/delete 미호출")
    void toggle_songNotFound_throwsAndSkipsRepoMutations() {
        given(songRepository.existsById(SONG_ID)).willReturn(false);

        assertThatThrownBy(() -> bookmarkService.toggle(SESSION_ID, SONG_ID))
                .isInstanceOf(SongNotFoundException.class)
                .hasMessageContaining(SONG_ID.toString());

        verify(bookmarkRepository, never()).save(any());
        verify(bookmarkRepository, never()).deleteBySessionIdAndSongId(any(), any());
    }

    @Test
    @DisplayName("toggle: 신규 시그널이면 active=true + save 호출, delete 미호출")
    void toggle_newSignal_savesAndReturnsActive() {
        given(songRepository.existsById(SONG_ID)).willReturn(true);
        given(bookmarkRepository.existsBySessionIdAndSongId(SESSION_ID, SONG_ID)).willReturn(false);

        final ToggleResult toggleResult = bookmarkService.toggle(SESSION_ID, SONG_ID);

        assertThat(toggleResult.active()).isTrue();
        assertThat(toggleResult.songId()).isEqualTo(SONG_ID);
        verify(bookmarkRepository, times(1)).save(any(Bookmark.class));
        verify(bookmarkRepository, never()).deleteBySessionIdAndSongId(any(), any());
    }

    @Test
    @DisplayName("toggle: 기존 시그널이면 active=false + delete 호출, save 미호출")
    void toggle_existingSignal_deletesAndReturnsInactive() {
        given(songRepository.existsById(SONG_ID)).willReturn(true);
        given(bookmarkRepository.existsBySessionIdAndSongId(SESSION_ID, SONG_ID)).willReturn(true);

        final ToggleResult toggleResult = bookmarkService.toggle(SESSION_ID, SONG_ID);

        assertThat(toggleResult.active()).isFalse();
        assertThat(toggleResult.songId()).isEqualTo(SONG_ID);
        verify(bookmarkRepository, times(1)).deleteBySessionIdAndSongId(SESSION_ID, SONG_ID);
        verify(bookmarkRepository, never()).save(any());
    }

    @Test
    @DisplayName("toggle 멱등 round-trip: 1회차 active=true → 2회차 active=false (save 1·delete 1)")
    void toggle_roundTrip_flipsActiveOnce() {
        given(songRepository.existsById(SONG_ID)).willReturn(true);
        given(bookmarkRepository.existsBySessionIdAndSongId(SESSION_ID, SONG_ID))
                .willReturn(false)
                .willReturn(true);

        final ToggleResult firstToggleResult = bookmarkService.toggle(SESSION_ID, SONG_ID);
        final ToggleResult secondToggleResult = bookmarkService.toggle(SESSION_ID, SONG_ID);

        assertThat(firstToggleResult.active()).isTrue();
        assertThat(secondToggleResult.active()).isFalse();
        verify(bookmarkRepository, times(1)).save(any(Bookmark.class));
        verify(bookmarkRepository, times(1)).deleteBySessionIdAndSongId(SESSION_ID, SONG_ID);
    }
}
