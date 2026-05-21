package com.mobruji.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.song.MetadataSource;
import com.mobruji.song.Mood;
import com.mobruji.song.MusicalKey;
import com.mobruji.song.Song;

class RecommendationScorerTest {

    @Test
    @DisplayName("voiceRangeFit: 곡 키 음역 중심이 사용자 음역에 완전 포함되면 1.0")
    void voiceRangeFit_fullyInside_returnsOne() {
        // C major root=60. 곡 음역 53~67. 사용자 50~80 → 완전 포함
        final double fit = RecommendationScorer.voiceRangeFit(MusicalKey.C_MAJOR, 50, 80);
        assertThat(fit).isEqualTo(1.0);
    }

    @Test
    @DisplayName("voiceRangeFit: 곡 음역과 사용자 음역이 전혀 겹치지 않으면 0.0")
    void voiceRangeFit_noOverlap_returnsZero() {
        // C major 53~67, 사용자 100~119 (벗어남)
        final double fit = RecommendationScorer.voiceRangeFit(MusicalKey.C_MAJOR, 100, 119);
        assertThat(fit).isEqualTo(0.0);
    }

    @Test
    @DisplayName("voiceRangeFit: UNKNOWN 키는 0.5(중립)")
    void voiceRangeFit_unknownKey_returnsNeutral() {
        assertThat(RecommendationScorer.voiceRangeFit(MusicalKey.UNKNOWN, 50, 80)).isEqualTo(0.5);
    }

    @Test
    @DisplayName("moodMatch: 같으면 1, 다르면 0, 요청 null이면 0")
    void moodMatch_cases() {
        assertThat(RecommendationScorer.moodMatch(Mood.UPBEAT, Mood.UPBEAT)).isEqualTo(1.0);
        assertThat(RecommendationScorer.moodMatch(Mood.UPBEAT, Mood.CALM)).isEqualTo(0.0);
        assertThat(RecommendationScorer.moodMatch(Mood.UPBEAT, null)).isEqualTo(0.0);
        assertThat(RecommendationScorer.moodMatch(null, Mood.UPBEAT)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("score: 음역 완전 일치 + mood 일치 시 0.5+0.2=0.7 근처(jitter ±0.01)")
    void score_perfectMatch_returnsExpected() {
        final Song song = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .mood(Mood.UPBEAT)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
        final Random fixedRandom = new Random(42);
        final RecommendationScorer.ScoreBreakdown breakdown = RecommendationScorer.score(song, 50, 80, Mood.UPBEAT,
                fixedRandom);

        assertThat(breakdown.voiceRangeFit()).isEqualTo(1.0);
        assertThat(breakdown.moodMatch()).isEqualTo(1.0);
        assertThat(breakdown.total()).isBetween(0.69, 0.71);
    }

    @Test
    @DisplayName("matchReason: 음역+분위기 모두 일치하면 통합 메시지")
    void toMatchReason_bothMatch() {
        final Song song = Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .mood(Mood.UPBEAT)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
        final RecommendationScorer.ScoreBreakdown breakdown = new RecommendationScorer.ScoreBreakdown(0.7, 1.0, 1.0);
        assertThat(breakdown.toMatchReason(song, Mood.UPBEAT)).contains("음역대").contains("분위기");
    }
}
