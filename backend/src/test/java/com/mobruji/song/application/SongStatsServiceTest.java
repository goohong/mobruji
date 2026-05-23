package com.mobruji.song.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.EnumMap;
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
    @DisplayName("getStats: 곡 0건이면 total=0, avg=0.0, 모든 source=0, lastAlbumCoverBackfillAt=null(미실행)")
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
        // 이슈 #863: AlbumCoverBackfillCommand 미실행 시각이 그대로 노출된다 (배치 실행 전에는 null).
        assertThat(stats.lastAlbumCoverBackfillAt()).isNull();
    }

    @Test
    @DisplayName("getStats: countByMetadataSource 가 동일 source 를 두 번 반환하면 마지막 row 가 승리한다 (put 시맨틱)")
    void getStats_duplicateSourceRow_lastWriteWins() {
        // given — 동일 source 가 중복으로 들어오는 비정상 시나리오 (e.g. group by 결손)
        final SongRepository repo = mock(SongRepository.class);
        when(repo.count()).thenReturn(40L);
        when(repo.countByMetadataSource()).thenReturn(List.of(
                countRow(MetadataSource.MANUAL_SEED, 10L),
                countRow(MetadataSource.MANUAL_SEED, 25L),
                countRow(MetadataSource.AUDIO_ANALYSIS, 5L)));
        when(repo.findAverageMetadataConfidence()).thenReturn(0.5);

        final SongStatsService service = new SongStatsService(repo);

        // when
        final SongStats stats = service.getStats();

        // then — 마지막 MANUAL_SEED row (25L) 가 승리, 누락된 다른 source 는 0
        assertThat(stats.byMetadataSource())
                .containsEntry(MetadataSource.MANUAL_SEED, 25L)
                .containsEntry(MetadataSource.AUDIO_ANALYSIS, 5L)
                .containsEntry(MetadataSource.EXTERNAL_API, 0L);
    }

    @Test
    @DisplayName("getStats: avg confidence 가 NaN 이어도 DTO 가 그대로 노출한다 (서비스 sanitize 없음)")
    void getStats_avgConfidenceNaN_passedThrough() {
        // given
        final SongRepository repo = mock(SongRepository.class);
        when(repo.count()).thenReturn(1L);
        when(repo.countByMetadataSource()).thenReturn(List.of());
        when(repo.findAverageMetadataConfidence()).thenReturn(Double.NaN);

        final SongStatsService service = new SongStatsService(repo);

        // when
        final SongStats stats = service.getStats();

        // then — NaN 은 그대로 통과 (서비스에서 별도 보정하지 않는다는 계약 가드)
        assertThat(Double.isNaN(stats.avgConfidence())).isTrue();
    }

    @Test
    @DisplayName("getStats: avg confidence 음수도 DTO 가 그대로 노출한다 (repository 가 산정한 값을 신뢰)")
    void getStats_avgConfidenceNegative_passedThrough() {
        // given
        final SongRepository repo = mock(SongRepository.class);
        when(repo.count()).thenReturn(1L);
        when(repo.countByMetadataSource()).thenReturn(List.of());
        when(repo.findAverageMetadataConfidence()).thenReturn(-0.1);

        final SongStatsService service = new SongStatsService(repo);

        // when
        final SongStats stats = service.getStats();

        // then — repository 산정값을 그대로 통과
        assertThat(stats.avgConfidence()).isEqualTo(-0.1);
    }

    @Test
    @DisplayName("getStats: byMetadataSource 는 EnumMap 인스턴스로 노출되어 키 순서가 enum 선언 순서와 일치한다 (결정성)")
    void getStats_byMetadataSource_isEnumMap() {
        // given
        final SongRepository repo = mock(SongRepository.class);
        when(repo.count()).thenReturn(0L);
        when(repo.countByMetadataSource()).thenReturn(List.of());
        when(repo.findAverageMetadataConfidence()).thenReturn(null);

        final SongStatsService service = new SongStatsService(repo);

        // when
        final SongStats stats = service.getStats();

        // then — EnumMap 인스턴스 (key 순서 결정성 보장 타입)
        assertThat(stats.byMetadataSource()).isInstanceOf(EnumMap.class);
        // 키 순서가 enum 선언 순서와 일치
        assertThat(stats.byMetadataSource().keySet())
                .containsExactly(MetadataSource.values());
    }
}
