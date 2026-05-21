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

    private static final RecommendationProperties.Diversity DEFAULT_DIVERSITY = new RecommendationProperties.Diversity(
            2, 4);

    /**
     * spec §9 초기 가중치.
     */
    private static RecommendationProperties defaultProperties() {
        return new RecommendationProperties(
                new RecommendationProperties.Weights(0.5, 0.2, 0.2, 0.1),
                DEFAULT_DIVERSITY, 10, 0.01, RecommendationProperties.SeedStrategy.DERIVED);
    }

    private static RecommendationScorer scorer(final RecommendationProperties properties) {
        return new RecommendationScorer(properties);
    }

    @Test
    @DisplayName("voiceRangeFit: 곡 키 음역 중심이 사용자 음역에 완전 포함되면 1.0")
    void voiceRangeFit_fullyInside_returnsOne() {
        // given: C major root=60. 곡 음역 53~67. 사용자 50~80 → 완전 포함
        // when
        final double fit = RecommendationScorer.voiceRangeFit(MusicalKey.C_MAJOR, 50, 80);
        // then
        assertThat(fit).isEqualTo(1.0);
    }

    @Test
    @DisplayName("voiceRangeFit: 곡 음역과 사용자 음역이 전혀 겹치지 않으면 0.0")
    void voiceRangeFit_noOverlap_returnsZero() {
        // given: C major 53~67, 사용자 100~119 (벗어남)
        // when
        final double fit = RecommendationScorer.voiceRangeFit(MusicalKey.C_MAJOR, 100, 119);
        // then
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
    @DisplayName("score: 음역 완전 일치 + mood 일치 시 voiceFit*1 + mood*1 + popularity*1 ± jitter")
    void score_perfectMatch_returnsExpected() {
        // given: 기본 가중치 voiceFit=0.5, mood=0.2, popularity=0.1
        final Song song = buildSong(MusicalKey.C_MAJOR, Mood.UPBEAT);
        final Random fixedRandom = new Random(42);
        // when
        final RecommendationScorer.ScoreBreakdown breakdown = scorer(defaultProperties()).score(song, 50, 80,
                Mood.UPBEAT, fixedRandom);
        // then: voiceFit 1.0 + mood 1.0 + popularity 1.0 (genre 0) → 0.5 + 0.2 + 0.1 = 0.8 ± 0.01
        assertThat(breakdown.voiceRangeFit()).isEqualTo(1.0);
        assertThat(breakdown.moodMatch()).isEqualTo(1.0);
        assertThat(breakdown.total()).isBetween(0.79, 0.81);
    }

    @Test
    @DisplayName("matchReason: 음역+분위기 모두 일치하면 통합 메시지")
    void toMatchReason_bothMatch() {
        final Song song = buildSong(MusicalKey.C_MAJOR, Mood.UPBEAT);
        final RecommendationScorer.ScoreBreakdown breakdown = new RecommendationScorer.ScoreBreakdown(0.7, 1.0, 1.0);
        assertThat(breakdown.toMatchReason(song, Mood.UPBEAT)).contains("음역대").contains("분위기");
    }

    @Test
    @DisplayName("가중치 튜닝: voiceFit 비중을 올리면 음역만 맞는 곡이 분위기만 맞는 곡보다 더 높게 나온다")
    void score_weightTuning_voiceFitDominates() {
        // given: 음역만 일치한 곡 A vs 분위기만 일치한 곡 B
        final Song voiceOnly = buildSong(MusicalKey.C_MAJOR, Mood.CALM);
        final Song moodOnly = buildSong(MusicalKey.UNKNOWN, Mood.UPBEAT); // voiceFit=0.5(중립)
        final RecommendationProperties voiceHeavy = new RecommendationProperties(
                new RecommendationProperties.Weights(0.8, 0.0, 0.1, 0.0),
                DEFAULT_DIVERSITY, 10, 0.0,
                RecommendationProperties.SeedStrategy.DERIVED); // jitter 0 → 결정적
        // when
        final double voiceScore = scorer(voiceHeavy)
                .score(voiceOnly, 50, 80, Mood.UPBEAT, new Random(0)).total();
        final double moodScore = scorer(voiceHeavy)
                .score(moodOnly, 50, 80, Mood.UPBEAT, new Random(0)).total();
        // then: voiceFit 1.0 * 0.8 = 0.8 vs voiceFit 0.5 * 0.8 + mood 1.0 * 0.1 = 0.5 → voiceOnly 우세
        assertThat(voiceScore).isGreaterThan(moodScore);
    }

    @Test
    @DisplayName("가중치 튜닝: mood 비중을 극단적으로 올리면 분위기만 맞는 곡이 음역만 맞는 곡을 앞선다")
    void score_weightTuning_moodDominates() {
        // given: 동일 두 곡에 mood-heavy 가중치
        final Song voiceOnly = buildSong(MusicalKey.C_MAJOR, Mood.CALM);
        final Song moodOnly = buildSong(MusicalKey.UNKNOWN, Mood.UPBEAT);
        final RecommendationProperties moodHeavy = new RecommendationProperties(
                new RecommendationProperties.Weights(0.1, 0.0, 0.8, 0.0),
                DEFAULT_DIVERSITY, 10, 0.0,
                RecommendationProperties.SeedStrategy.DERIVED);
        // when
        final double voiceScore = scorer(moodHeavy)
                .score(voiceOnly, 50, 80, Mood.UPBEAT, new Random(0)).total();
        final double moodScore = scorer(moodHeavy)
                .score(moodOnly, 50, 80, Mood.UPBEAT, new Random(0)).total();
        // then: voiceFit 1.0 * 0.1 = 0.1 vs voiceFit 0.5 * 0.1 + mood 1.0 * 0.8 = 0.85 → moodOnly 우세
        assertThat(moodScore).isGreaterThan(voiceScore);
    }

    private static Song buildSong(final MusicalKey key, final Mood mood) {
        return Song.builder()
                .title("t").artist("a")
                .keyOriginal(key)
                .mood(mood)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
    }
}
