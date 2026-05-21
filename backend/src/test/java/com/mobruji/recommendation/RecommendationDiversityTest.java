package com.mobruji.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.recommendation.RecommendationScorer.ScoreBreakdown;
import com.mobruji.recommendation.RecommendationService.ScoredSong;
import com.mobruji.song.MetadataSource;
import com.mobruji.song.Mood;
import com.mobruji.song.MusicalKey;
import com.mobruji.song.Song;

class RecommendationDiversityTest {

    @Test
    @DisplayName("같은 아티스트는 최대 2곡까지")
    void diversity_capsSameArtist() {
        final List<ScoredSong> sorted = List.of(
                scored("a-1", "Artist A", "팝", 0.9),
                scored("a-2", "Artist A", "팝", 0.85),
                scored("a-3", "Artist A", "팝", 0.8),
                scored("b-1", "Artist B", "팝", 0.7));

        final List<ScoredSong> result = RecommendationService.applyDiversity(sorted, 5);

        assertThat(result).hasSize(3); // Artist A 2곡 + Artist B 1곡
        assertThat(result.stream().filter(scoredSong -> scoredSong.song().getArtist().equals("Artist A")).count())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("같은 장르는 최대 4곡까지")
    void diversity_capsSameGenre() {
        final List<ScoredSong> sorted = List.of(
                scored("a", "A1", "발라드", 0.9),
                scored("b", "A2", "발라드", 0.85),
                scored("c", "A3", "발라드", 0.8),
                scored("d", "A4", "발라드", 0.75),
                scored("e", "A5", "발라드", 0.7),
                scored("f", "A6", "댄스", 0.65));

        final List<ScoredSong> result = RecommendationService.applyDiversity(sorted, 10);

        assertThat(result).hasSize(5); // 발라드 4곡 + 댄스 1곡
        assertThat(result.stream().filter(scoredSong -> "발라드".equals(scoredSong.song().getGenre())).count()).isEqualTo(
                4);
    }

    @Test
    @DisplayName("resultCount 도달 시 멈춤")
    void diversity_stopsAtResultCount() {
        final List<ScoredSong> sorted = List.of(
                scored("a", "A1", "팝", 0.9),
                scored("b", "A2", "팝", 0.85),
                scored("c", "A3", "팝", 0.8));

        final List<ScoredSong> result = RecommendationService.applyDiversity(sorted, 2);

        assertThat(result).hasSize(2);
    }

    private static ScoredSong scored(final String title, final String artist, final String genre, final double score) {
        final Song song = Song.builder()
                .title(title).artist(artist)
                .keyOriginal(MusicalKey.C_MAJOR)
                .mood(Mood.UPBEAT)
                .genre(genre)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
        return new ScoredSong(song, new ScoreBreakdown(score, 1.0, 1.0));
    }
}
