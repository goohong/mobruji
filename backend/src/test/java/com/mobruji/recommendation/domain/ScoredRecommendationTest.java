package com.mobruji.recommendation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;

class ScoredRecommendationTest {

    private static Song sampleSong() {
        return Song.builder()
                .title("샘플")
                .artist("Artist")
                .keyOriginal(MusicalKey.C_MAJOR)
                .mood(Mood.UPBEAT)
                .genre("팝")
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
    }

    @Test
    @DisplayName("정상: 5인자 canonical 생성자는 breakdown까지 보존한다")
    void canonical_preservesBreakdown() {
        // given
        final Song song = sampleSong();
        final ScoreBreakdown breakdown = new ScoreBreakdown(0.9, 0.8, 0.7, 0.6, 0.5, 0.4);

        // when
        final ScoredRecommendation scored = new ScoredRecommendation(song, 0.75, "match", 3, breakdown);

        // then
        assertThat(scored.song()).isSameAs(song);
        assertThat(scored.score()).isEqualTo(0.75);
        assertThat(scored.matchReason()).isEqualTo("match");
        assertThat(scored.rankPosition()).isEqualTo(3);
        assertThat(scored.breakdown()).isSameAs(breakdown);
    }

    @Test
    @DisplayName("편의 생성자: breakdown 없는 4인자 호출이면 breakdown은 null")
    void convenience_breakdownNull() {
        // when
        final ScoredRecommendation scored = new ScoredRecommendation(sampleSong(), 0.5, "match", 1);

        // then
        assertThat(scored.breakdown()).isNull();
    }

    @Test
    @DisplayName("null guard: song이 null이면 NullPointerException")
    void create_nullSong_throws() {
        assertThatThrownBy(() -> new ScoredRecommendation(null, 0.5, "match", 1, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("song");
    }

    @Test
    @DisplayName("null guard: matchReason이 null이면 NullPointerException")
    void create_nullMatchReason_throws() {
        assertThatThrownBy(() -> new ScoredRecommendation(sampleSong(), 0.5, null, 1, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("matchReason");
    }

    @Test
    @DisplayName("invariant: rankPosition이 0 이하이면 IllegalArgumentException")
    void create_invalidRank_throws() {
        assertThatThrownBy(() -> new ScoredRecommendation(sampleSong(), 0.5, "match", 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rankPosition");
        assertThatThrownBy(() -> new ScoredRecommendation(sampleSong(), 0.5, "match", -1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rankPosition");
    }
}
