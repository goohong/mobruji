package com.mobruji.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mobruji.recommendation.domain.TrendingSong;
import com.mobruji.recommendation.domain.TrendingSongAggregate;
import com.mobruji.recommendation.infrastructure.RecommendationRepository;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

/**
 * {@link TrendingService} 단위 가드 (#1488). 집계 결과의 정렬/limit/곡 메타 join/누락 skip/기간 환산을 잠근다.
 *
 * <p>DB 집계 자체는 {@code TrendingIntegrationTest} 가 E2E 로 검증하므로, 본 단위 테스트는 repository 가
 * 반환한 집계 행을 application 이 어떻게 순위화·매핑하는지에 집중한다.
 */
@ExtendWith(MockitoExtension.class)
class TrendingServiceTest {

    @Mock
    private RecommendationRepository recommendationRepository;

    @Mock
    private SongRepository songRepository;

    @InjectMocks
    private TrendingService trendingService;

    @Test
    @DisplayName("인기도 DESC → 등장횟수 DESC → songId ASC 순으로 순위 부여")
    void sortsByPopularityThenAppearanceThenSongId() throws Exception {
        given(recommendationRepository.aggregateTrending(any(), isNull(), isNull(), isNull()))
                .willReturn(List.of(
                        aggregate(1L, 2L, 1.0),
                        aggregate(2L, 5L, 2.5),
                        aggregate(3L, 2L, 1.0)));
        given(songRepository.findAllById(any())).willReturn(List.of(
                song(1L), song(2L), song(3L)));

        final List<TrendingSong> result = trendingService.getTrending(
                new TrendingQuery(7, null, null, null, 10));

        // 2(2.5) > {1,3 동점 1.0} → 등장횟수 동일(2) → songId ASC → 1, 3
        assertThat(result).extracting(trending -> trending.song().getId()).containsExactly(2L, 1L, 3L);
        assertThat(result).extracting(TrendingSong::rankPosition).containsExactly(1, 2, 3);
        assertThat(result.get(0).appearanceCount()).isEqualTo(5L);
    }

    @Test
    @DisplayName("limit 만큼만 상위 곡 반환")
    void appliesLimit() throws Exception {
        given(recommendationRepository.aggregateTrending(any(), isNull(), isNull(), isNull()))
                .willReturn(List.of(
                        aggregate(1L, 1L, 0.5),
                        aggregate(2L, 3L, 3.0),
                        aggregate(3L, 2L, 1.0)));
        given(songRepository.findAllById(any())).willReturn(List.of(song(2L), song(3L)));

        final List<TrendingSong> result = trendingService.getTrending(
                new TrendingQuery(7, null, null, null, 2));

        assertThat(result).extracting(trending -> trending.song().getId()).containsExactly(2L, 3L);
    }

    @Test
    @DisplayName("카탈로그에서 사라진 곡(songId 미존재)은 결과에서 skip")
    void skipsMissingSong() throws Exception {
        given(recommendationRepository.aggregateTrending(any(), isNull(), isNull(), isNull()))
                .willReturn(List.of(
                        aggregate(1L, 5L, 2.5),
                        aggregate(99L, 3L, 2.0)));
        // 99L 은 Song 테이블에 없음 — findAllById 가 1L 만 반환
        given(songRepository.findAllById(any())).willReturn(List.of(song(1L)));

        final List<TrendingSong> result = trendingService.getTrending(
                new TrendingQuery(7, null, null, null, 10));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).song().getId()).isEqualTo(1L);
        assertThat(result.get(0).rankPosition()).isEqualTo(1);
    }

    @Test
    @DisplayName("집계 결과가 비면 곡 조회 없이 빈 리스트")
    void emptyAggregateReturnsEmpty() {
        given(recommendationRepository.aggregateTrending(any(), isNull(), isNull(), isNull()))
                .willReturn(List.of());

        final List<TrendingSong> result = trendingService.getTrending(
                new TrendingQuery(7, null, null, null, 10));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("periodDays 를 since = now - periodDays 로 환산해 repository 에 전달, mood/range 도 전파")
    void translatesPeriodAndFilters() throws Exception {
        given(recommendationRepository.aggregateTrending(any(), eq(Mood.UPBEAT), eq(50), eq(60)))
                .willReturn(List.of(aggregate(1L, 1L, 1.0)));
        given(songRepository.findAllById(any())).willReturn(List.of(song(1L)));
        final ArgumentCaptor<LocalDateTime> sinceCaptor = ArgumentCaptor.forClass(LocalDateTime.class);

        final LocalDateTime before = LocalDateTime.now().minusDays(30);
        trendingService.getTrending(new TrendingQuery(30, Mood.UPBEAT, 50, 60, 10));
        final LocalDateTime after = LocalDateTime.now().minusDays(30);

        org.mockito.Mockito.verify(recommendationRepository)
                .aggregateTrending(sinceCaptor.capture(), eq(Mood.UPBEAT), eq(50), eq(60));
        assertThat(sinceCaptor.getValue()).isBetween(before, after);
    }

    private static TrendingSongAggregate aggregate(
            final Long songId, final long appearanceCount, final double popularityScore) {
        return new TrendingSongAggregate() {
            @Override
            public Long getSongId() {
                return songId;
            }

            @Override
            public long getAppearanceCount() {
                return appearanceCount;
            }

            @Override
            public double getPopularityScore() {
                return popularityScore;
            }
        };
    }

    private static Song song(final Long id) throws Exception {
        final Song song = Song.builder()
                .title("title-" + id).artist("artist-" + id)
                .keyOriginal(MusicalKey.C_MAJOR)
                .mood(Mood.UPBEAT)
                .genre("팝")
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
        final Field idField = Song.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(song, id);
        return song;
    }
}
