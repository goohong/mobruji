package com.mobruji.recommendation.application;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mobruji.recommendation.domain.TrendingSong;
import com.mobruji.recommendation.domain.TrendingSongAggregate;
import com.mobruji.recommendation.infrastructure.RecommendationRepository;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

import lombok.RequiredArgsConstructor;

/**
 * 트렌딩(다른 사용자 인기곡) 조회 유스케이스 (#1488).
 *
 * <p>spec: {@code docs/features/trending-recommendation.md}.
 *
 * <p>추천 결과 히스토리({@code recommendation} row)를 기간/분위기/음역대로 필터링·집계해 인기곡 순위를 만든다.
 * 읽기 전용이라 추천 알고리즘(점수 계산) 결정성에는 영향이 없다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class TrendingService {

    private final RecommendationRepository recommendationRepository;
    private final SongRepository songRepository;

    public List<TrendingSong> getTrending(final TrendingQuery trendingQuery) {
        final LocalDateTime since = LocalDateTime.now().minusDays(trendingQuery.periodDays());
        final List<TrendingSongAggregate> aggregates = recommendationRepository.aggregateTrending(
                since,
                trendingQuery.mood(),
                trendingQuery.voiceRangeLow(),
                trendingQuery.voiceRangeHigh());
        if (aggregates.isEmpty()) {
            return List.of();
        }

        // 정렬: 인기도(rank 감쇠 합) → 등장 횟수 → songId 순. provider 별 ORDER BY alias 차이를 피해
        // application 에서 결정성 있게 처리 (같은 데이터면 항상 같은 순위).
        final List<TrendingSongAggregate> ranked = aggregates.stream()
                .sorted(Comparator
                        .comparingDouble(TrendingSongAggregate::getPopularityScore).reversed()
                        .thenComparing(Comparator.comparingLong(TrendingSongAggregate::getAppearanceCount).reversed())
                        .thenComparing(TrendingSongAggregate::getSongId))
                .limit(trendingQuery.limit())
                .toList();

        // 곡 메타데이터를 1쿼리로 join (N+1 회피). 카탈로그에서 사라진 곡은 응답에서 skip — history GET 과 같은 정책.
        final List<Long> songIds = ranked.stream().map(TrendingSongAggregate::getSongId).toList();
        final Map<Long, Song> songsById = new HashMap<>();
        songRepository.findAllById(songIds).forEach(song -> songsById.put(song.getId(), song));

        final List<TrendingSong> trendingSongs = new ArrayList<>(ranked.size());
        int rankPosition = 1;
        for (final TrendingSongAggregate aggregate : ranked) {
            final Song song = songsById.get(aggregate.getSongId());
            if (song == null) {
                continue;
            }
            trendingSongs.add(new TrendingSong(
                    song,
                    rankPosition,
                    aggregate.getAppearanceCount(),
                    aggregate.getPopularityScore()));
            rankPosition++;
        }
        return trendingSongs;
    }
}
