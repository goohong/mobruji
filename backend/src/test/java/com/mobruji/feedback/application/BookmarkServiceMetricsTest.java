package com.mobruji.feedback.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mobruji.feedback.infrastructure.BookmarkRepository;
import com.mobruji.song.domain.SongNotFoundException;
import com.mobruji.song.infrastructure.SongRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * spec: {@code docs/features/recommendation-history-and-feedback.md} §3 비기능 — toggle 분기 직후
 * {@code mobruji.recommendationhistory.bookmark.created} / {@code mobruji.recommendationhistory.bookmark.deleted}
 * 카운터 +1 보장. {@link LikeServiceMetricsTest} 와 mirror 패턴.
 */
@ExtendWith(MockitoExtension.class)
class BookmarkServiceMetricsTest {

    private static final String METRIC_BOOKMARK_CREATED = "mobruji.recommendationhistory.bookmark.created";
    private static final String METRIC_BOOKMARK_DELETED = "mobruji.recommendationhistory.bookmark.deleted";
    private static final String SESSION_ID = "session-metric-bm";
    private static final Long SONG_ID = 101L;

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
    @DisplayName("toggle: 신규 시그널 → bookmark.created 카운터 +1, bookmark.deleted 미증가")
    void toggle_newSignal_incrementsCreatedCounter() {
        given(songRepository.existsById(SONG_ID)).willReturn(true);
        given(bookmarkRepository.existsBySessionIdAndSongId(SESSION_ID, SONG_ID)).willReturn(false);

        bookmarkService.toggle(SESSION_ID, SONG_ID);

        assertThat(counterValue(METRIC_BOOKMARK_CREATED)).isEqualTo(1.0);
        assertThat(counterValue(METRIC_BOOKMARK_DELETED)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("toggle: 기존 시그널 → bookmark.deleted 카운터 +1, bookmark.created 미증가")
    void toggle_existingSignal_incrementsDeletedCounter() {
        given(songRepository.existsById(SONG_ID)).willReturn(true);
        given(bookmarkRepository.existsBySessionIdAndSongId(SESSION_ID, SONG_ID)).willReturn(true);

        bookmarkService.toggle(SESSION_ID, SONG_ID);

        assertThat(counterValue(METRIC_BOOKMARK_DELETED)).isEqualTo(1.0);
        assertThat(counterValue(METRIC_BOOKMARK_CREATED)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("toggle round-trip: 1회차 created+1, 2회차 deleted+1 (각 누적 1.0)")
    void toggle_roundTrip_incrementsBothCountersOnce() {
        given(songRepository.existsById(SONG_ID)).willReturn(true);
        given(bookmarkRepository.existsBySessionIdAndSongId(SESSION_ID, SONG_ID))
                .willReturn(false)
                .willReturn(true);

        bookmarkService.toggle(SESSION_ID, SONG_ID);
        bookmarkService.toggle(SESSION_ID, SONG_ID);

        assertThat(counterValue(METRIC_BOOKMARK_CREATED)).isEqualTo(1.0);
        assertThat(counterValue(METRIC_BOOKMARK_DELETED)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("toggle: 미존재 songId → 두 카운터 모두 미증가 (회귀 가드)")
    void toggle_songNotFound_doesNotIncrementEitherCounter() {
        given(songRepository.existsById(SONG_ID)).willReturn(false);

        assertThatThrownBy(() -> bookmarkService.toggle(SESSION_ID, SONG_ID))
                .isInstanceOf(SongNotFoundException.class);

        assertThat(counterValue(METRIC_BOOKMARK_CREATED)).isEqualTo(0.0);
        assertThat(counterValue(METRIC_BOOKMARK_DELETED)).isEqualTo(0.0);
    }

    private double counterValue(final String metricName) {
        final Counter counter = meterRegistry.find(metricName).counter();
        if (counter == null) {
            return 0.0;
        }
        return counter.count();
    }
}
