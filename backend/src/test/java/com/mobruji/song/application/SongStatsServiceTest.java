package com.mobruji.song.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.infrastructure.SongRepository;
import com.mobruji.song.infrastructure.SongRepository.MetadataSourceCount;

/**
 * {@link SongStatsService} 단위 테스트 — repository projection 을 mock 으로 주입해 응답 shape 을 검증한다.
 */
class SongStatsServiceTest {

    private static MetadataSourceCount countRow(final MetadataSource source, final long count) {
        return new MetadataSourceCount() {
            @Override
            public MetadataSource getMetadataSource() {
                return source;
            }

            @Override
            public long getCount() {
                return count;
            }
        };
    }

    @Test
    @DisplayName("getStats: 일부 source 만 row 가 있어도 모든 enum 키가 0 으로 채워진다")
    void getStats_fillsZeroForMissingSources() {
        // given
        final SongRepository repo = mock(SongRepository.class);
        when(repo.count()).thenReturn(30L);
        when(repo.countByMetadataSource()).thenReturn(List.of(
                countRow(MetadataSource.MANUAL_SEED, 25L),
                countRow(MetadataSource.AUDIO_ANALYSIS, 5L)));
        when(repo.findAverageMetadataConfidence()).thenReturn(0.82);

        final SongStatsService service = new SongStatsService(repo);

        // when
        final SongStats stats = service.getStats();

        // then
        assertThat(stats.total()).isEqualTo(30L);
        assertThat(stats.avgConfidence()).isEqualTo(0.82);
        assertThat(stats.byMetadataSource())
                .containsEntry(MetadataSource.MANUAL_SEED, 25L)
                .containsEntry(MetadataSource.AUDIO_ANALYSIS, 5L)
                .containsEntry(MetadataSource.EXTERNAL_API, 0L)
                .containsEntry(MetadataSource.USER_CONTRIBUTION, 0L)
                .containsEntry(MetadataSource.INFERRED, 0L);
    }

    @Test
    @DisplayName("getStats: 곡 0건이면 total=0, avg=0.0, 모든 source=0")
    void getStats_emptyDb_returnsZeros() {
        // given
        final SongRepository repo = mock(SongRepository.class);
        when(repo.count()).thenReturn(0L);
        when(repo.countByMetadataSource()).thenReturn(List.of());
        when(repo.findAverageMetadataConfidence()).thenReturn(null);

        final SongStatsService service = new SongStatsService(repo);

        // when
        final SongStats stats = service.getStats();

        // then
        assertThat(stats.total()).isZero();
        assertThat(stats.avgConfidence()).isEqualTo(0.0);
        assertThat(stats.byMetadataSource()).hasSize(MetadataSource.values().length);
        assertThat(stats.byMetadataSource().values()).allMatch(count -> count == 0L);
    }
}
