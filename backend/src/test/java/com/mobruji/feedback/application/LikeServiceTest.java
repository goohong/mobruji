package com.mobruji.feedback.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mobruji.feedback.domain.Like;
import com.mobruji.feedback.infrastructure.LikeRepository;
import com.mobruji.song.domain.SongNotFoundException;
import com.mobruji.song.infrastructure.SongRepository;

/**
 * LikeService#toggle 분기 회귀 가드. 4개 시나리오: 미존재 song / 신규 / 기존 / 멱등 round-trip.
 */
@ExtendWith(MockitoExtension.class)
class LikeServiceTest {

    private static final String SESSION_ID = "session-abc";
    private static final Long SONG_ID = 42L;

    @Mock
    private LikeRepository likeRepository;

    @Mock
    private SongRepository songRepository;

    @InjectMocks
    private LikeService likeService;

    @Test
    @DisplayName("toggle: 미존재 songId면 SongNotFoundException + save/delete 미호출")
    void toggle_songNotFound_throwsAndSkipsRepoMutations() {
        given(songRepository.existsById(SONG_ID)).willReturn(false);

        assertThatThrownBy(() -> likeService.toggle(SESSION_ID, SONG_ID))
                .isInstanceOf(SongNotFoundException.class)
                .hasMessageContaining(SONG_ID.toString());

        verify(likeRepository, never()).save(any());
        verify(likeRepository, never()).deleteBySessionIdAndSongId(any(), any());
    }

    @Test
    @DisplayName("toggle: 신규 시그널이면 active=true + save 호출, delete 미호출")
    void toggle_newSignal_savesAndReturnsActive() {
        given(songRepository.existsById(SONG_ID)).willReturn(true);
        given(likeRepository.existsBySessionIdAndSongId(SESSION_ID, SONG_ID)).willReturn(false);

        final ToggleResult toggleResult = likeService.toggle(SESSION_ID, SONG_ID);

        assertThat(toggleResult.active()).isTrue();
        assertThat(toggleResult.songId()).isEqualTo(SONG_ID);
        verify(likeRepository, times(1)).save(any(Like.class));
        verify(likeRepository, never()).deleteBySessionIdAndSongId(any(), any());
    }

    @Test
    @DisplayName("toggle: 기존 시그널이면 active=false + delete 호출, save 미호출")
    void toggle_existingSignal_deletesAndReturnsInactive() {
        given(songRepository.existsById(SONG_ID)).willReturn(true);
        given(likeRepository.existsBySessionIdAndSongId(SESSION_ID, SONG_ID)).willReturn(true);

        final ToggleResult toggleResult = likeService.toggle(SESSION_ID, SONG_ID);

        assertThat(toggleResult.active()).isFalse();
        assertThat(toggleResult.songId()).isEqualTo(SONG_ID);
        verify(likeRepository, times(1)).deleteBySessionIdAndSongId(SESSION_ID, SONG_ID);
        verify(likeRepository, never()).save(any());
    }

    @Test
    @DisplayName("toggle 멱등 round-trip: 1회차 active=true → 2회차 active=false (save 1·delete 1)")
    void toggle_roundTrip_flipsActiveOnce() {
        given(songRepository.existsById(SONG_ID)).willReturn(true);
        // 1회차: 미존재 → save, 2회차: 존재 → delete
        given(likeRepository.existsBySessionIdAndSongId(SESSION_ID, SONG_ID))
                .willReturn(false)
                .willReturn(true);

        final ToggleResult firstToggleResult = likeService.toggle(SESSION_ID, SONG_ID);
        final ToggleResult secondToggleResult = likeService.toggle(SESSION_ID, SONG_ID);

        assertThat(firstToggleResult.active()).isTrue();
        assertThat(secondToggleResult.active()).isFalse();
        verify(likeRepository, times(1)).save(any(Like.class));
        verify(likeRepository, times(1)).deleteBySessionIdAndSongId(SESSION_ID, SONG_ID);
    }
}
