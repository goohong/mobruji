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

    @Test
    @DisplayName("score (경계): 모든 가중치가 0이면 total은 jitter 범위 [-jitterMagnitude, +jitterMagnitude] 안")
    void score_allWeightsZero_totalWithinJitterRange() {
        // given: 모든 가중치 0 + jitter 0.01
        final Song song = buildSong(MusicalKey.C_MAJOR, Mood.UPBEAT, 120);
        final RecommendationProperties allZero = new RecommendationProperties(
                new RecommendationProperties.Weights(0.0, 0.0, 0.0, 0.0, 0.0),
                DEFAULT_DIVERSITY, defaultTempo(), 10, 0.01,
                RecommendationProperties.SeedStrategy.DERIVED);
        // when
        final RecommendationScorer.Scored scored = scorer(allZero)
                .score(song, 50, 80, Mood.UPBEAT, 120, new Random(42));
        // then: total은 [-0.01, 0.01] 내 (가중 합산이 0이라 jitter만 남는다)
        assertThat(scored.total()).isBetween(-0.01, 0.01);
    }

    @Test
    @DisplayName("score (경계): 모든 가중치 0 + jitter 0 이면 total 정확히 0.0")
    void score_allWeightsAndJitterZero_totalIsZero() {
        // given
        final Song song = buildSong(MusicalKey.C_MAJOR, Mood.UPBEAT, 120);
        final RecommendationProperties dead = new RecommendationProperties(
                new RecommendationProperties.Weights(0.0, 0.0, 0.0, 0.0, 0.0),
                DEFAULT_DIVERSITY, defaultTempo(), 10, 0.0,
                RecommendationProperties.SeedStrategy.DERIVED);
        // when
        final RecommendationScorer.Scored scored = scorer(dead)
                .score(song, 50, 80, Mood.UPBEAT, 120, new Random(0));
        // then
        assertThat(scored.total()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("score (경계): voiceFit 만 1.0 가중치, 완전 매칭 곡 → total == 1.0 (jitter 0)")
    void score_onlyVoiceFitWeight_perfectMatch() {
        // given: voiceFit 1.0 단일 신호, 나머지 0
        final Song song = buildSong(MusicalKey.C_MAJOR, null, null);
        final RecommendationProperties voiceOnly = new RecommendationProperties(
                new RecommendationProperties.Weights(1.0, 0.0, 0.0, 0.0, 0.0),
                DEFAULT_DIVERSITY, defaultTempo(), 10, 0.0,
                RecommendationProperties.SeedStrategy.DERIVED);
        // when: 음역 완전 포함
        final RecommendationScorer.Scored scored = scorer(voiceOnly)
                .score(song, 50, 80, null, null, new Random(0));
        // then: rangeFit=1.0 * 1.0 = 1.0
        assertThat(scored.total()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("score (경계): mood 만 1.0 가중치, 분위기 일치 → total == 1.0")
    void score_onlyMoodWeight_match() {
        // given
        final Song song = buildSong(MusicalKey.UNKNOWN, Mood.CALM, null);
        final RecommendationProperties moodOnly = new RecommendationProperties(
                new RecommendationProperties.Weights(0.0, 0.0, 1.0, 0.0, 0.0),
                DEFAULT_DIVERSITY, defaultTempo(), 10, 0.0,
                RecommendationProperties.SeedStrategy.DERIVED);
        // when
        final RecommendationScorer.Scored scored = scorer(moodOnly)
                .score(song, 50, 80, Mood.CALM, null, new Random(0));
        // then: moodMatch=1.0 * 1.0
        assertThat(scored.total()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("score (경계): popularity 만 1.0 가중치 → v1 popularity=1.0 고정이라 total 항상 1.0")
    void score_onlyPopularityWeight_alwaysOne() {
        // given
        final Song song = buildSong(MusicalKey.UNKNOWN, null, null);
        final RecommendationProperties popOnly = new RecommendationProperties(
                new RecommendationProperties.Weights(0.0, 0.0, 0.0, 1.0, 0.0),
                DEFAULT_DIVERSITY, defaultTempo(), 10, 0.0,
                RecommendationProperties.SeedStrategy.DERIVED);
        // when
        final RecommendationScorer.Scored scored = scorer(popOnly)
                .score(song, 50, 80, null, null, new Random(0));
        // then: popularity 신호=1.0 * 가중치 1.0
        assertThat(scored.total()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("score (경계): genre 만 1.0 가중치 → v1 genre=0.0 고정이라 total 항상 0.0")
    void score_onlyGenreWeight_alwaysZero() {
        // given
        final Song song = buildSong(MusicalKey.C_MAJOR, Mood.UPBEAT, 120);
        final RecommendationProperties genreOnly = new RecommendationProperties(
                new RecommendationProperties.Weights(0.0, 1.0, 0.0, 0.0, 0.0),
                DEFAULT_DIVERSITY, defaultTempo(), 10, 0.0,
                RecommendationProperties.SeedStrategy.DERIVED);
        // when
        final RecommendationScorer.Scored scored = scorer(genreOnly)
                .score(song, 50, 80, Mood.UPBEAT, 120, new Random(0));
        // then: genre 신호=0.0 → 가중치 무관 항상 0.0
        assertThat(scored.total()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("score (경계): 6신호 모두 최대값 + 모든 가중치 1.0 → total == 가중치 합 (popularity 신호=1, genre는 0이라 4.0)")
    void score_allSignalsMax_totalEqualsWeightSum() {
        // given: voiceFit=1, genre 신호=0(고정), mood=1, popularity=1, tempo=1 → 합 1+0+1+1+1 = 4
        final Song song = buildSong(MusicalKey.C_MAJOR, Mood.UPBEAT, 128);
        final RecommendationProperties allOnes = new RecommendationProperties(
                new RecommendationProperties.Weights(1.0, 1.0, 1.0, 1.0, 1.0),
                DEFAULT_DIVERSITY, defaultTempo(), 10, 0.0,
                RecommendationProperties.SeedStrategy.DERIVED);
        // when: UPBEAT mood + preferredBpm 128 → tempoMatch=1.0
        final RecommendationScorer.Scored scored = scorer(allOnes)
                .score(song, 50, 80, Mood.UPBEAT, 128, new Random(0));
        // then: 1 + 0 + 1 + 1 + 1 = 4.0
        assertThat(scored.total()).isEqualTo(4.0);
    }

    @Test
    @DisplayName("score (invariant): breakdown raw 신호값은 가중치 변경에도 동일하게 보존된다")
    void score_breakdownRawSignalsAreWeightInvariant() {
        // given: 동일 입력, 가중치만 다른 두 properties
        final Song song = buildSong(MusicalKey.C_MAJOR, Mood.UPBEAT, 128);
        final RecommendationProperties propsA = new RecommendationProperties(
                new RecommendationProperties.Weights(0.5, 0.2, 0.2, 0.1, 0.1),
                DEFAULT_DIVERSITY, defaultTempo(), 10, 0.0,
                RecommendationProperties.SeedStrategy.DERIVED);
        final RecommendationProperties propsB = new RecommendationProperties(
                new RecommendationProperties.Weights(0.1, 0.0, 0.9, 0.0, 0.0),
                DEFAULT_DIVERSITY, defaultTempo(), 10, 0.0,
                RecommendationProperties.SeedStrategy.DERIVED);
        // when
        final ScoreBreakdown breakdownA = scorer(propsA)
                .score(song, 50, 80, Mood.UPBEAT, 128, new Random(0)).breakdown();
        final ScoreBreakdown breakdownB = scorer(propsB)
                .score(song, 50, 80, Mood.UPBEAT, 128, new Random(0)).breakdown();
        // then: raw 신호는 가중치와 독립 — 동일해야 한다
        assertThat(breakdownA.keyMatch()).isEqualTo(breakdownB.keyMatch());
        assertThat(breakdownA.rangeFit()).isEqualTo(breakdownB.rangeFit());
        assertThat(breakdownA.genreMatch()).isEqualTo(breakdownB.genreMatch());
        assertThat(breakdownA.moodMatch()).isEqualTo(breakdownB.moodMatch());
        assertThat(breakdownA.popularity()).isEqualTo(breakdownB.popularity());
        assertThat(breakdownA.tempoMatch()).isEqualTo(breakdownB.tempoMatch());
    }

    @Test
    @DisplayName("score (경계): tempoMatch 만 가중치 1.0 + 곡 BPM null → 중립 0.5 → total 0.5")
    void score_onlyTempoWeight_nullSongBpm_neutralHalf() {
        // given
        final Song song = buildSong(MusicalKey.UNKNOWN, null, null);
        final RecommendationProperties tempoOnly = new RecommendationProperties(
                new RecommendationProperties.Weights(0.0, 0.0, 0.0, 0.0, 1.0),
                DEFAULT_DIVERSITY, defaultTempo(), 10, 0.0,
                RecommendationProperties.SeedStrategy.DERIVED);
        // when
        final RecommendationScorer.Scored scored = scorer(tempoOnly)
                .score(song, 50, 80, null, null, new Random(0));
        // then: tempoMatch 중립 0.5 * 가중치 1.0 = 0.5
        assertThat(scored.total()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("score (invariant): 동일 입력 + 동일 seed → 결정적 (total/breakdown 모두 일치)")
    void score_sameInputSameSeed_deterministic() {
        // given
        final Song song = buildSong(MusicalKey.C_MAJOR, Mood.UPBEAT, 120);
        final RecommendationProperties props = defaultProperties();
        // when: 동일 seed 두 번
        final RecommendationScorer.Scored a = scorer(props).score(song, 50, 80, Mood.UPBEAT, 120, new Random(7));
        final RecommendationScorer.Scored b = scorer(props).score(song, 50, 80, Mood.UPBEAT, 120, new Random(7));
        // then: 가중 합산과 raw 신호 모두 동일
        assertThat(a.total()).isEqualTo(b.total());
        assertThat(a.breakdown()).isEqualTo(b.breakdown());
    }

    @Test
    @DisplayName("tempoMatch (회귀 가드): mood 표에 해당 mood 엔트리 없음 + fallbackBpm 존재 → fallbackBpm 사용")
    void tempoMatch_moodMissingFromTable_fallsBackToFallbackBpm() {
        // given: moodDefaultBpm 표에 UPBEAT 만 등록, fallbackBpm=110
        final Map<Mood, Integer> onlyUpbeat = new EnumMap<>(Mood.class);
        onlyUpbeat.put(Mood.UPBEAT, 128);
        final RecommendationProperties.Tempo tempo = new RecommendationProperties.Tempo(40.0, onlyUpbeat, 110);
        // when: 입력 mood=CALM (표에 없음) → fallbackBpm(110) 사용, songBpm=110 → distance 0
        // then
        assertThat(RecommendationScorer.tempoMatch(110, null, Mood.CALM, tempo)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("tempoMatch (회귀 가드): mood 표에 엔트리 없음 + fallbackBpm null → target 결정 불가 → 중립 0.5")
    void tempoMatch_moodMissingFromTableAndNoFallback_returnsNeutral() {
        // given: 빈 표, fallbackBpm null
        final Map<Mood, Integer> empty = new EnumMap<>(Mood.class);
        final RecommendationProperties.Tempo tempo = new RecommendationProperties.Tempo(40.0, empty, null);
        // when: mood 입력 있음에도 표/fallback 모두 부재 → resolveTargetBpm null
        // then: songBpm 있어도 target 부재로 neutral 0.5
        assertThat(RecommendationScorer.tempoMatch(120, null, Mood.NOSTALGIC, tempo)).isEqualTo(0.5);
    }

    @Test
    @DisplayName("tempoMatch (회귀 가드): distance == tolerance 정확 경계 → 정확히 0.0 (clamp 시작점)")
    void tempoMatch_distanceEqualsToleranceExact_returnsZero() {
        // given: tolerance=40, songBpm=80, preferredBpm=120 → distance=40 정확
        // when / then: 1.0 - min(1.0, 40/40) = 0.0
        assertThat(RecommendationScorer.tempoMatch(80, 120, null, defaultTempo())).isEqualTo(0.0);
    }

    @Test
    @DisplayName("tempoMatch (회귀 가드): tolerance 최소값(1.0) — distance=0 → 1.0, distance=1 → 0.0")
    void tempoMatch_minimumTolerance_boundaryBehavior() {
        // given: tolerance=1.0 (Bean Validation @DecimalMin 최소값)
        final Map<Mood, Integer> empty = new EnumMap<>(Mood.class);
        final RecommendationProperties.Tempo tightTempo = new RecommendationProperties.Tempo(1.0, empty, 120);
        // when / then: 정확 매칭
        assertThat(RecommendationScorer.tempoMatch(120, 120, null, tightTempo)).isEqualTo(1.0);
        // distance 1 = tolerance → 0.0
        assertThat(RecommendationScorer.tempoMatch(121, 120, null, tightTempo)).isEqualTo(0.0);
        // distance 2 > tolerance → clamp 0.0 (음수 방지)
        assertThat(RecommendationScorer.tempoMatch(122, 120, null, tightTempo)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("tempoMatch (회귀 가드): distance 가 tolerance 직전이면 양수 정밀값 보존")
    void tempoMatch_distanceJustBelowTolerance_preservesPrecision() {
        // given: tolerance=40, distance=39 → 1.0 - 39/40 = 0.025
        // when
        final double signal = RecommendationScorer.tempoMatch(159, 120, null, defaultTempo());
        // then: clamp 이전 정밀값
        assertThat(signal).isEqualTo(1.0 - 39.0 / 40.0);
        assertThat(signal).isGreaterThan(0.0);
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
