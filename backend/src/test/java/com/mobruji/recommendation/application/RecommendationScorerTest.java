package com.mobruji.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.recommendation.domain.ScoreBreakdown;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;

class RecommendationScorerTest {

    private static final RecommendationProperties.Diversity DEFAULT_DIVERSITY = new RecommendationProperties.Diversity(
            2, 4);

    private static RecommendationProperties.Tempo defaultTempo() {
        final Map<Mood, Integer> moodDefaults = new EnumMap<>(Mood.class);
        moodDefaults.put(Mood.UPBEAT, 128);
        moodDefaults.put(Mood.CALM, 70);
        moodDefaults.put(Mood.EMOTIONAL, 80);
        moodDefaults.put(Mood.POWERFUL, 140);
        moodDefaults.put(Mood.GROOVY, 110);
        moodDefaults.put(Mood.NOSTALGIC, 90);
        return new RecommendationProperties.Tempo(40.0, moodDefaults, 110);
    }

    /**
     * spec §9 v2 가중치 (tempoMatch 0.1 추가).
     */
    private static RecommendationProperties defaultProperties() {
        return new RecommendationProperties(
                new RecommendationProperties.Weights(0.5, 0.2, 0.2, 0.1, 0.1),
                DEFAULT_DIVERSITY, defaultTempo(), 10, 0.01,
                RecommendationProperties.SeedStrategy.DERIVED);
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
    @DisplayName("keyMatch: 알려진 키는 1.0, UNKNOWN/null은 0.5")
    void keyMatch_cases() {
        assertThat(RecommendationScorer.keyMatch(MusicalKey.C_MAJOR)).isEqualTo(1.0);
        assertThat(RecommendationScorer.keyMatch(MusicalKey.A_MAJOR)).isEqualTo(1.0);
        assertThat(RecommendationScorer.keyMatch(MusicalKey.UNKNOWN)).isEqualTo(0.5);
        assertThat(RecommendationScorer.keyMatch(null)).isEqualTo(0.5);
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
    @DisplayName("tempoMatch (v2): songBpm == preferredBpm 이면 1.0 (정확 매칭)")
    void tempoMatch_exactMatch_returnsOne() {
        assertThat(RecommendationScorer.tempoMatch(120, 120, null, defaultTempo())).isEqualTo(1.0);
    }

    @Test
    @DisplayName("tempoMatch (v2): 거리 == tolerance 이면 0.0, 절반이면 0.5 (선형 감쇠)")
    void tempoMatch_linearDecay() {
        // tolerance=40
        assertThat(RecommendationScorer.tempoMatch(160, 120, null, defaultTempo())).isEqualTo(0.0);
        assertThat(RecommendationScorer.tempoMatch(140, 120, null, defaultTempo())).isEqualTo(0.5);
        assertThat(RecommendationScorer.tempoMatch(100, 120, null, defaultTempo())).isEqualTo(0.5);
    }

    @Test
    @DisplayName("tempoMatch (v2): tolerance 초과는 clamp 0.0")
    void tempoMatch_beyondTolerance_clampedToZero() {
        assertThat(RecommendationScorer.tempoMatch(200, 120, null, defaultTempo())).isEqualTo(0.0);
        assertThat(RecommendationScorer.tempoMatch(60, 120, null, defaultTempo())).isEqualTo(0.0);
    }

    @Test
    @DisplayName("tempoMatch (v2): preferredBpm null + mood 입력 시 mood default 사용")
    void tempoMatch_moodDefault() {
        // CALM default = 70. songBpm 70 → 1.0
        assertThat(RecommendationScorer.tempoMatch(70, null, Mood.CALM, defaultTempo())).isEqualTo(1.0);
        // UPBEAT default = 128. songBpm 108 → distance 20 → 0.5
        assertThat(RecommendationScorer.tempoMatch(108, null, Mood.UPBEAT, defaultTempo())).isEqualTo(0.5);
    }

    @Test
    @DisplayName("tempoMatch (v2): preferredBpm 우선 — mood default 무시")
    void tempoMatch_preferredOverridesMood() {
        // preferredBpm=120 우선, UPBEAT default(128) 무시
        assertThat(RecommendationScorer.tempoMatch(120, 120, Mood.UPBEAT, defaultTempo())).isEqualTo(1.0);
    }

    @Test
    @DisplayName("tempoMatch (v2): 곡 BPM null 이면 0.5 (중립, 정보 없음)")
    void tempoMatch_nullSongBpm_returnsNeutral() {
        assertThat(RecommendationScorer.tempoMatch(null, 120, Mood.UPBEAT, defaultTempo())).isEqualTo(0.5);
    }

    @Test
    @DisplayName("tempoMatch (v2): preferredBpm/mood/fallback 모두 결정 불가면 0.5")
    void tempoMatch_noTarget_returnsNeutral() {
        // fallbackBpm null, mood null, preferredBpm null → target 없음 → 0.5
        final Map<Mood, Integer> empty = new EnumMap<>(Mood.class);
        final RecommendationProperties.Tempo tempoNoFallback = new RecommendationProperties.Tempo(40.0, empty, null);
        assertThat(RecommendationScorer.tempoMatch(120, null, null, tempoNoFallback)).isEqualTo(0.5);
    }

    @Test
    @DisplayName("tempoMatch (v2): mood default 없으면 fallbackBpm 사용")
    void tempoMatch_fallbackBpm() {
        final Map<Mood, Integer> empty = new EnumMap<>(Mood.class);
        final RecommendationProperties.Tempo tempoFallbackOnly = new RecommendationProperties.Tempo(40.0, empty, 110);
        // fallbackBpm=110, songBpm=110 → 1.0
        assertThat(RecommendationScorer.tempoMatch(110, null, Mood.UPBEAT, tempoFallbackOnly)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("score: 음역 완전 일치 + mood 일치 + tempo 완전 일치 시 voiceFit*1 + mood*1 + popularity*1 + tempo*1 ± jitter")
    void score_perfectMatch_returnsExpected() {
        // given: 기본 가중치 voiceFit=0.5, mood=0.2, popularity=0.1, tempoMatch=0.1
        final Song song = buildSong(MusicalKey.C_MAJOR, Mood.UPBEAT, 128);
        final Random fixedRandom = new Random(42);
        // when: UPBEAT mood + preferredBpm=128 → tempoMatch=1.0
        final RecommendationScorer.Scored scored = scorer(defaultProperties()).score(song, 50, 80,
                Mood.UPBEAT, 128, fixedRandom);
        // then: 0.5 + 0.2 + 0.1 + 0.1 = 0.9 ± 0.01
        assertThat(scored.voiceRangeFit()).isEqualTo(1.0);
        assertThat(scored.moodMatch()).isEqualTo(1.0);
        assertThat(scored.tempoMatch()).isEqualTo(1.0);
        assertThat(scored.total()).isBetween(0.89, 0.91);
    }

    @Test
    @DisplayName("score: breakdown 6신호가 모두 [0,1] 범위 안에 있다 (raw 신호 보존)")
    void score_breakdownAllFieldsInUnitInterval() {
        final Song song = buildSong(MusicalKey.C_MAJOR, Mood.UPBEAT, 120);
        final RecommendationScorer.Scored scored = scorer(defaultProperties())
                .score(song, 50, 80, Mood.UPBEAT, 120, new Random(0));
        final ScoreBreakdown breakdown = scored.breakdown();
        assertThat(breakdown.keyMatch()).isBetween(0.0, 1.0);
        assertThat(breakdown.rangeFit()).isBetween(0.0, 1.0);
        assertThat(breakdown.genreMatch()).isBetween(0.0, 1.0);
        assertThat(breakdown.moodMatch()).isBetween(0.0, 1.0);
        assertThat(breakdown.popularity()).isBetween(0.0, 1.0);
        assertThat(breakdown.tempoMatch()).isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("score: breakdown — 알려진 키는 keyMatch=1.0, genreMatch=0(v1), popularity=1.0(v1)")
    void score_breakdownKnownKeyShape() {
        final Song song = buildSong(MusicalKey.C_MAJOR, Mood.UPBEAT, 120);
        final RecommendationScorer.Scored scored = scorer(defaultProperties())
                .score(song, 50, 80, Mood.UPBEAT, 120, new Random(0));
        assertThat(scored.breakdown().keyMatch()).isEqualTo(1.0);
        assertThat(scored.breakdown().genreMatch()).isEqualTo(0.0);
        assertThat(scored.breakdown().popularity()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("score: UNKNOWN 키는 keyMatch=0.5 (중립)")
    void score_breakdownUnknownKey() {
        final Song song = buildSong(MusicalKey.UNKNOWN, Mood.UPBEAT, 120);
        final RecommendationScorer.Scored scored = scorer(defaultProperties())
                .score(song, 50, 80, Mood.UPBEAT, 120, new Random(0));
        assertThat(scored.breakdown().keyMatch()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("matchReason: 음역+분위기 모두 일치하면 통합 메시지")
    void toMatchReason_bothMatch() {
        final Song song = buildSong(MusicalKey.C_MAJOR, Mood.UPBEAT, 120);
        // total=0.7, breakdown: rangeFit=1.0, moodMatch=1.0, tempoMatch=1.0
        final ScoreBreakdown breakdown = new ScoreBreakdown(1.0, 1.0, 0.0, 1.0, 1.0, 1.0);
        final RecommendationScorer.Scored scored = new RecommendationScorer.Scored(0.7, breakdown);
        assertThat(scored.toMatchReason(song, Mood.UPBEAT)).contains("음역대").contains("분위기");
    }

    @Test
    @DisplayName("가중치 튜닝: voiceFit 비중을 올리면 음역만 맞는 곡이 분위기만 맞는 곡보다 더 높게 나온다")
    void score_weightTuning_voiceFitDominates() {
        // given: 음역만 일치한 곡 A vs 분위기만 일치한 곡 B
        final Song voiceOnly = buildSong(MusicalKey.C_MAJOR, Mood.CALM, 120);
        final Song moodOnly = buildSong(MusicalKey.UNKNOWN, Mood.UPBEAT, 120); // voiceFit=0.5(중립)
        final RecommendationProperties voiceHeavy = new RecommendationProperties(
                new RecommendationProperties.Weights(0.8, 0.0, 0.1, 0.0, 0.0),
                DEFAULT_DIVERSITY, defaultTempo(), 10, 0.0,
                RecommendationProperties.SeedStrategy.DERIVED); // jitter 0 → 결정적
        // when
        final double voiceScore = scorer(voiceHeavy)
                .score(voiceOnly, 50, 80, Mood.UPBEAT, 120, new Random(0)).total();
        final double moodScore = scorer(voiceHeavy)
                .score(moodOnly, 50, 80, Mood.UPBEAT, 120, new Random(0)).total();
        // then: voiceFit 1.0 * 0.8 = 0.8 vs voiceFit 0.5 * 0.8 + mood 1.0 * 0.1 = 0.5 → voiceOnly 우세
        assertThat(voiceScore).isGreaterThan(moodScore);
    }

    @Test
    @DisplayName("가중치 튜닝: mood 비중을 극단적으로 올리면 분위기만 맞는 곡이 음역만 맞는 곡을 앞선다")
    void score_weightTuning_moodDominates() {
        // given: 동일 두 곡에 mood-heavy 가중치
        final Song voiceOnly = buildSong(MusicalKey.C_MAJOR, Mood.CALM, 120);
        final Song moodOnly = buildSong(MusicalKey.UNKNOWN, Mood.UPBEAT, 120);
        final RecommendationProperties moodHeavy = new RecommendationProperties(
                new RecommendationProperties.Weights(0.1, 0.0, 0.8, 0.0, 0.0),
                DEFAULT_DIVERSITY, defaultTempo(), 10, 0.0,
                RecommendationProperties.SeedStrategy.DERIVED);
        // when
        final double voiceScore = scorer(moodHeavy)
                .score(voiceOnly, 50, 80, Mood.UPBEAT, 120, new Random(0)).total();
        final double moodScore = scorer(moodHeavy)
                .score(moodOnly, 50, 80, Mood.UPBEAT, 120, new Random(0)).total();
        // then: voiceFit 1.0 * 0.1 = 0.1 vs voiceFit 0.5 * 0.1 + mood 1.0 * 0.8 = 0.85 → moodOnly 우세
        assertThat(moodScore).isGreaterThan(voiceScore);
    }

    @Test
    @DisplayName("가중치 튜닝(v2): tempoMatch 비중이 크면 BPM 적합 곡이 BPM 동떨어진 곡보다 우세")
    void score_weightTuning_tempoDominates() {
        // given: 같은 키/음역, BPM만 다른 두 곡. tempoMatch만 다름.
        final Song fastSong = buildSong(MusicalKey.UNKNOWN, null, 130); // 음역중립 0.5
        final Song slowSong = buildSong(MusicalKey.UNKNOWN, null, 60);
        final RecommendationProperties tempoHeavy = new RecommendationProperties(
                new RecommendationProperties.Weights(0.0, 0.0, 0.0, 0.0, 1.0),
                DEFAULT_DIVERSITY, defaultTempo(), 10, 0.0,
                RecommendationProperties.SeedStrategy.DERIVED);
        // when: preferredBpm=130 → fast 곡이 정확 매칭, slow 곡은 distance=70 > tolerance(40) → 0.0
        final double fastScore = scorer(tempoHeavy)
                .score(fastSong, 50, 80, null, 130, new Random(0)).total();
        final double slowScore = scorer(tempoHeavy)
                .score(slowSong, 50, 80, null, 130, new Random(0)).total();
        // then
        assertThat(fastScore).isGreaterThan(slowScore);
    }

    private static Song buildSong(final MusicalKey key, final Mood mood, final Integer bpm) {
        return Song.builder()
                .title("t").artist("a")
                .keyOriginal(key)
                .bpm(bpm)
                .mood(mood)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
    }
}
