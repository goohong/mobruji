package com.mobruji.song.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.song.application.SongStats;
import com.mobruji.song.domain.MetadataSource;

/**
 * {@link SongStatsResponse#from(SongStats)} factory 회귀 가드.
 *
 * <p>spec rev 14 후속(#208/#212/#863) — admin {@code GET /api/v1/songs/stats} 응답이 운영 가시성을 책임진다.
 * lastBackfillAt / lastAlbumCoverBackfillAt 는 미실행 시 {@code null} 이며 매핑 누락 시 운영자가 batch
 * 미실행을 감지할 수 없게 되므로 본 테스트로 잠근다.
 */
class SongStatsResponseTest {

    @Test
    @DisplayName("from: 모든 필드 정상 매핑 — total/byMetadataSource/avgConfidence/lastBackfillAt/lastAlbumCoverBackfillAt")
    void from_mapsAllFields() {
        // given
        final Map<MetadataSource, Long> byMetadataSource = new EnumMap<>(MetadataSource.class);
        byMetadataSource.put(MetadataSource.MANUAL_SEED, 10L);
        byMetadataSource.put(MetadataSource.EXTERNAL_API, 5L);
        byMetadataSource.put(MetadataSource.USER_CONTRIBUTION, 0L);
        byMetadataSource.put(MetadataSource.INFERRED, 2L);
        byMetadataSource.put(MetadataSource.AUDIO_ANALYSIS, 3L);
        final Instant lastBackfillAt = Instant.parse("2026-05-23T10:00:00Z");
        final Instant lastAlbumCoverBackfillAt = Instant.parse("2026-05-24T11:00:00Z");
        final SongStats songStats = new SongStats(
                20L, byMetadataSource, 0.85, lastBackfillAt, lastAlbumCoverBackfillAt);

        // when
        final SongStatsResponse songStatsResponse = SongStatsResponse.from(songStats);

        // then
        assertThat(songStatsResponse.total()).isEqualTo(20L);
        assertThat(songStatsResponse.byMetadataSource()).isEqualTo(byMetadataSource);
        assertThat(songStatsResponse.avgConfidence()).isEqualTo(0.85);
        assertThat(songStatsResponse.lastBackfillAt()).isEqualTo(lastBackfillAt);
        assertThat(songStatsResponse.lastAlbumCoverBackfillAt()).isEqualTo(lastAlbumCoverBackfillAt);
    }

    @Test
    @DisplayName("from: lastBackfillAt null — batch 미실행 시 그대로 null 전달")
    void from_whenLastBackfillAtNull_preservesNull() {
        // given
        final Map<MetadataSource, Long> byMetadataSource = new EnumMap<>(MetadataSource.class);
        byMetadataSource.put(MetadataSource.MANUAL_SEED, 1L);
        final SongStats songStats = new SongStats(
                1L, byMetadataSource, 1.0, null, Instant.parse("2026-05-24T00:00:00Z"));

        // when
        final SongStatsResponse songStatsResponse = SongStatsResponse.from(songStats);

        // then
        assertThat(songStatsResponse.lastBackfillAt()).isNull();
        assertThat(songStatsResponse.lastAlbumCoverBackfillAt()).isNotNull();
    }

    @Test
    @DisplayName("from: lastAlbumCoverBackfillAt null — album cover batch 미실행 시 그대로 null 전달 (이슈 #863)")
    void from_whenLastAlbumCoverBackfillAtNull_preservesNull() {
        // given
        final Map<MetadataSource, Long> byMetadataSource = new EnumMap<>(MetadataSource.class);
        byMetadataSource.put(MetadataSource.MANUAL_SEED, 1L);
        final SongStats songStats = new SongStats(
                1L, byMetadataSource, 1.0, Instant.parse("2026-05-24T00:00:00Z"), null);

        // when
        final SongStatsResponse songStatsResponse = SongStatsResponse.from(songStats);

        // then
        assertThat(songStatsResponse.lastBackfillAt()).isNotNull();
        assertThat(songStatsResponse.lastAlbumCoverBackfillAt()).isNull();
    }

    @Test
    @DisplayName("from: 곡 0 건 + 두 backfill 모두 null — 신규 환경 부팅 직후 상태")
    void from_whenEmptyAndBothNull_preservesAll() {
        // given
        final SongStats songStats = new SongStats(
                0L, new EnumMap<>(MetadataSource.class), 0.0, null, null);

        // when
        final SongStatsResponse songStatsResponse = SongStatsResponse.from(songStats);

        // then
        assertThat(songStatsResponse.total()).isZero();
        assertThat(songStatsResponse.byMetadataSource()).isEmpty();
        assertThat(songStatsResponse.avgConfidence()).isZero();
        assertThat(songStatsResponse.lastBackfillAt()).isNull();
        assertThat(songStatsResponse.lastAlbumCoverBackfillAt()).isNull();
    }
}
