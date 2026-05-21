package com.mobruji.recommendation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.mobruji.recommendation.RecommendationService.ScoredSong;

/**
 * 점수 내림차순으로 정렬된 후보 리스트에 다양성 캡을 적용한다.
 *
 * <p>spec: {@code docs/features/recommendation-algorithm-v1.md} §9 Q4
 * — 같은 아티스트 ≤ {@code maxSameArtist}, 같은 장르 ≤ {@code maxSameGenre}.
 *
 * <p>fallback: 캡 통과 후보가 {@code resultCount}에 미달하면, 캡에서 제외됐던 후보를 점수 순으로 부족분만큼 채워 넣는다.
 * spec에 명시된 fallback 룰은 없으나 "결과 개수가 요청 size에 못 미치면 보강" 의도를 반영해 두 단계로 분리했다.
 */
@Component
public class DiversityPostProcessor {

    private final RecommendationProperties recommendationProperties;

    public DiversityPostProcessor(final RecommendationProperties recommendationProperties) {
        this.recommendationProperties = recommendationProperties;
    }

    /**
     * @param sortedCandidates 점수 내림차순으로 정렬된 후보.
     * @param resultCount      요청 결과 개수.
     * @return 캡을 적용한 결과 리스트. 크기는 {@code <= resultCount}.
     */
    public List<ScoredSong> apply(final List<ScoredSong> sortedCandidates, final int resultCount) {
        final RecommendationProperties.Diversity diversity = recommendationProperties.diversity();
        final int maxSameArtist = diversity.maxSameArtist();
        final int maxSameGenre = diversity.maxSameGenre();

        final List<ScoredSong> selected = new ArrayList<>();
        final List<ScoredSong> skipped = new ArrayList<>();
        final Map<String, Integer> artistCount = new HashMap<>();
        final Map<String, Integer> genreCount = new HashMap<>();

        // 1차: 캡을 지키며 선발
        for (final ScoredSong candidate : sortedCandidates) {
            if (selected.size() >= resultCount) {
                break;
            }
            final String artist = candidate.song().getArtist();
            final String genre = normalizeGenre(candidate.song().getGenre());
            if (artistCount.getOrDefault(artist, 0) >= maxSameArtist) {
                skipped.add(candidate);
                continue;
            }
            if (!genre.isEmpty() && genreCount.getOrDefault(genre, 0) >= maxSameGenre) {
                skipped.add(candidate);
                continue;
            }
            selected.add(candidate);
            artistCount.merge(artist, 1, Integer::sum);
            if (!genre.isEmpty()) {
                genreCount.merge(genre, 1, Integer::sum);
            }
        }

        // 2차 fallback: 부족하면 캡에 막혔던 후보로 보강 (순서는 원래 점수 순)
        if (selected.size() < resultCount && !skipped.isEmpty()) {
            final Set<ScoredSong> already = new HashSet<>(selected);
            for (final ScoredSong candidate : skipped) {
                if (selected.size() >= resultCount) {
                    break;
                }
                if (already.add(candidate)) {
                    selected.add(candidate);
                }
            }
        }

        return selected;
    }

    private static String normalizeGenre(final String rawGenre) {
        return rawGenre == null ? "" : rawGenre;
    }
}
