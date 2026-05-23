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

import com.mobruji.feedback.infrastructure.LikeRepository;
import com.mobruji.song.domain.SongNotFoundException;
import com.mobruji.song.infrastructure.SongRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * spec: {@code docs/features/recommendation-history-and-feedback.md} §3 비기능 — toggle 분기 직후
 * {@code mobruji.recommendationhistory.like.created} / {@code mobruji.recommendationhistory.like.deleted}
 * 카운터 +1 보장. spec {@code docs/features/observability-baseline.md} §5-3 단일 진실 정합.
 */
@ExtendWith(MockitoExtension.class)
class LikeServiceMetricsTest {

    private static final String METRIC_LIKE_CREATED = "mobruji.recommendationhistory.like.created";
    private static final String METRIC_LIKE_DELETED = "mobruji.recommendationhistory.like.deleted";
    private static final String SESSION_ID = "session-metric";
    private static final Long SONG_ID = 99L;

    @Mock
    private LikeRepository likeRepository;

    @Mock
    private SongRepository songRepository;

    private SimpleMeterRegistry meterRegistry;
    private LikeService likeService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        likeService = new LikeService(likeRepository, songRepository, meterRegistry);
    }

    @Test
    @DisplayName("toggle: 신규 시그널 → like.created 카운터 +1, like.deleted 미증가")
    void toggle_newSignal_incrementsCreatedCounter() {
        given(songRepository.existsById(SONG_ID)).willReturn(true);
        given(likeRepository.existsBySessionIdAndSongId(SESSION_ID, SONG_ID)).willReturn(false);

        likeService.toggle(SESSION_ID, SONG_ID);

        assertThat(counterValue(METRIC_LIKE_CREATED)).isEqualTo(1.0);
        assertThat(counterValue(METRIC_LIKE_DELETED)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("toggle: 기존 시그널 → like.deleted 카운터 +1, like.created 미증가")
    void toggle_existingSignal_incrementsDeletedCounter() {
        given(songRepository.existsById(SONG_ID)).willReturn(true);
        given(likeRepository.existsBySessionIdAndSongId(SESSION_ID, SONG_ID)).willReturn(true);

        likeService.toggle(SESSION_ID, SONG_ID);

        assertThat(counterValue(METRIC_LIKE_DELETED)).isEqualTo(1.0);
        assertThat(counterValue(METRIC_LIKE_CREATED)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("toggle round-trip: 1회차 created+1, 2회차 deleted+1 (각 누적 1.0)")
    void toggle_roundTrip_incrementsBothCountersOnce() {
        given(songRepository.existsById(SONG_ID)).willReturn(true);
        given(likeRepository.existsBySessionIdAndSongId(SESSION_ID, SONG_ID))
                .willReturn(false)
                .willReturn(true);

        likeService.toggle(SESSION_ID, SONG_ID);
        likeService.toggle(SESSION_ID, SONG_ID);

        assertThat(counterValue(METRIC_LIKE_CREATED)).isEqualTo(1.0);
        assertThat(counterValue(METRIC_LIKE_DELETED)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("toggle: 미존재 songId → 두 카운터 모두 미증가 (회귀 가드)")
    void toggle_songNotFound_doesNotIncrementEitherCounter() {
        given(songRepository.existsById(SONG_ID)).willReturn(false);

        assertThatThrownBy(() -> likeService.toggle(SESSION_ID, SONG_ID))
                .isInstanceOf(SongNotFoundException.class);

        assertThat(counterValue(METRIC_LIKE_CREATED)).isEqualTo(0.0);
        assertThat(counterValue(METRIC_LIKE_DELETED)).isEqualTo(0.0);
    }

    private double counterValue(final String metricName) {
        final Counter counter = meterRegistry.find(metricName).counter();
        if (counter == null) {
            return 0.0;
        }
        return counter.count();
    }
}
