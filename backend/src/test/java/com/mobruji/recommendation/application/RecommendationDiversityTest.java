package com.mobruji.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.recommendation.application.RecommendationScorer.Scored;
import com.mobruji.recommendation.application.RecommendationService.ScoredSong;
import com.mobruji.recommendation.domain.ScoreBreakdown;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;

class RecommendationDiversityTest {

    private static DiversityPostProcessor diversityPostProcessor(final int maxSameArtist, final int maxSameGenre) {
        final RecommendationProperties properties = new RecommendationProperties(
                new RecommendationProperties.Weights(0.5, 0.2, 0.2, 0.1, 0.1, 0.0, 0.0),
                new RecommendationProperties.Diversity(maxSameArtist, maxSameGenre),
                new RecommendationProperties.Tempo(40.0, java.util.Map.of(), 110),
                new RecommendationProperties.Generation(15.0, java.util.Map.of()), new RecommendationProperties.Gender(
                        0.6, 0.5, 0.3),
                10,
                0.01,
                RecommendationProperties.SeedStrategy.DERIVED);
        return new DiversityPostProcessor(properties);
    }

    @Test
    @DisplayName("같은 아티스트는 최대 2곡까지 (fallback 없는 경우)")
    void diversity_capsSameArtist() {
        // given: Artist A 3곡 + Artist B 1곡, resultCount=3 → A 2곡 + B 1곡 = 3
        final List<ScoredSong> sorted = List.of(
                scored("a-1", "Artist A", "팝", 0.9),
                scored("a-2", "Artist A", "팝", 0.85),
                scored("a-3", "Artist A", "팝", 0.8),
                scored("b-1", "Artist B", "팝", 0.7));

        // when
        final List<ScoredSong> result = diversityPostProcessor(2, 4).apply(sorted, 3);

        // then
        assertThat(result).hasSize(3);
        assertThat(result.stream().filter(scoredSong -> scoredSong.song().getArtist().equals("Artist A")).count())
                .isEqualTo(2);
        assertThat(result.stream().filter(scoredSong -> scoredSong.song().getArtist().equals("Artist B")).count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("같은 장르는 최대 4곡까지")
    void diversity_capsSameGenre() {
        // given: 발라드 5곡 + 댄스 1곡, resultCount=10 → 발라드 4곡 + 댄스 1곡 + (fallback) 발라드 1곡
        // fallback 없이 결과가 5라면 cap이 올바로 작동했다는 의미. resultCount를 5로 두자.
        final List<ScoredSong> sorted = List.of(
                scored("a", "A1", "발라드", 0.9),
                scored("b", "A2", "발라드", 0.85),
                scored("c", "A3", "발라드", 0.8),
                scored("d", "A4", "발라드", 0.75),
                scored("e", "A5", "발라드", 0.7),
                scored("f", "A6", "댄스", 0.65));

        // when
        final List<ScoredSong> result = diversityPostProcessor(2, 4).apply(sorted, 5);

        // then: 발라드 4 + 댄스 1
        assertThat(result).hasSize(5);
        assertThat(result.stream().filter(scoredSong -> "발라드".equals(scoredSong.song().getGenre())).count())
                .isEqualTo(4);
    }

    @Test
    @DisplayName("resultCount 도달 시 멈춤")
    void diversity_stopsAtResultCount() {
        final List<ScoredSong> sorted = List.of(
                scored("a", "A1", "팝", 0.9),
                scored("b", "A2", "팝", 0.85),
                scored("c", "A3", "팝", 0.8));

        final List<ScoredSong> result = diversityPostProcessor(2, 4).apply(sorted, 2);

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("같은 아티스트 후보만 들어와도 캡으로 최대 2곡까지 깎임")
    void diversity_onlySameArtist_capsToTwo() {
        // given: Artist Solo 5곡만 들어옴. resultCount=5지만 캡 2개 + fallback 5개 채움
        // 단, 본 케이스는 "cap이 결과를 깎는지" 확인이 목적 — fallback이 같은 아티스트로 다시 채우는 동작은 기대됨.
        // 따라서 fallback과 cap-only를 구분하려면 maxSameArtist=2로 두고 resultCount=2를 요청.
        final List<ScoredSong> sorted = List.of(
                scored("s1", "Solo", "팝", 0.9),
                scored("s2", "Solo", "팝", 0.85),
                scored("s3", "Solo", "팝", 0.8),
                scored("s4", "Solo", "팝", 0.75),
                scored("s5", "Solo", "팝", 0.7));

        // when: resultCount=2 → 정확히 cap 2개로 멈춤
        final List<ScoredSong> capped = diversityPostProcessor(2, 4).apply(sorted, 2);

        // then
        assertThat(capped).hasSize(2);
        assertThat(capped.stream().map(scoredSong -> scoredSong.song().getTitle())).containsExactly("s1", "s2");
    }

    @Test
    @DisplayName("fallback: 캡으로 부족분이 생기면 점수 순으로 캡 무시 후보를 채워 넣는다")
    void diversity_fallback_fillsShortage() {
        // given: Solo 5곡만 존재. resultCount=4 → cap으로 2곡만 통과해도 fallback이 2곡 더 채움
        final List<ScoredSong> sorted = List.of(
                scored("s1", "Solo", "팝", 0.9),
                scored("s2", "Solo", "팝", 0.85),
                scored("s3", "Solo", "팝", 0.8),
                scored("s4", "Solo", "팝", 0.75),
                scored("s5", "Solo", "팝", 0.7));

        // when
        final List<ScoredSong> result = diversityPostProcessor(2, 4).apply(sorted, 4);

        // then: 1차 2곡 + fallback 2곡 = 4곡
        assertThat(result).hasSize(4);
        assertThat(result.stream().map(scoredSong -> scoredSong.song().getTitle()))
                .containsExactly("s1", "s2", "s3", "s4");
    }

    private static ScoredSong scored(final String title, final String artist, final String genre, final double score) {
        final Song song = Song.builder()
                .title(title).artist(artist)
                .keyOriginal(MusicalKey.C_MAJOR)
                .mood(Mood.UPBEAT)
                .genre(genre)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
        // diversity 후처리는 total만 사용 — breakdown 6신호는 임의로 채워 둠.
        final ScoreBreakdown breakdown = new ScoreBreakdown(1.0, 1.0, 0.0, 1.0, 1.0, 0.5, 0.0, 0.0);
        return new ScoredSong(song, new Scored(score, breakdown));
    }
}
