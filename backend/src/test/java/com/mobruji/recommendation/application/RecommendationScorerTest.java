package com.mobruji.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.recommendation.domain.AgeGroup;
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

    private static RecommendationProperties.Generation defaultGeneration() {
        final Map<com.mobruji.recommendation.domain.AgeGroup, Integer> representativeYear = new EnumMap<>(
                com.mobruji.recommendation.domain.AgeGroup.class);
        representativeYear.put(com.mobruji.recommendation.domain.AgeGroup.TEENS, 2022);
        representativeYear.put(com.mobruji.recommendation.domain.AgeGroup.TWENTIES, 2015);
        representativeYear.put(com.mobruji.recommendation.domain.AgeGroup.THIRTIES, 2005);
        representativeYear.put(com.mobruji.recommendation.domain.AgeGroup.FORTIES, 1995);
        representativeYear.put(com.mobruji.recommendation.domain.AgeGroup.FIFTIES, 1985);
        representativeYear.put(com.mobruji.recommendation.domain.AgeGroup.SIXTIES_PLUS, 1975);
        return new RecommendationProperties.Generation(15.0, representativeYear);
    }

    /**
     * spec §9 v2 가중치 (tempoMatch 0.1 추가).
     */
    private static RecommendationProperties defaultProperties() {
        return new RecommendationProperties(
                new RecommendationProperties.Weights(0.5, 0.2, 0.2, 0.1, 0.1, 0.0),
                DEFAULT_DIVERSITY, defaultTempo(), defaultGeneration(), 10, 0.01,
                RecommendationProperties.SeedStrategy.DERIVED);
    }

    private static RecommendationScorer scorer(final RecommendationProperties properties) {
        return new RecommendationScorer(properties);
    }

    @Test
    @DisplayName("voiceRangeFit: 곡 음역 완전 포함 + 곡 키 중심이 사용자 음역 중앙이면 1.0 (reachability·centeredness 모두 최대)")
    void voiceRangeFit_fullyInsideAndCentered_returnsOne() {
        // given: C major root=60. 곡 음역 53~67. 사용자 53~67 → reachability=1.0, center=60=root → centeredness=1.0
        // when
        final double fit = RecommendationScorer.voiceRangeFit(MusicalKey.C_MAJOR, 53, 67);
        // then
        assertThat(fit).isEqualTo(1.0);
    }

    @Test
    @DisplayName("voiceRangeFit (#1452): 곡 음역을 완전 포함해도 곡 키 중심이 음역 중앙에서 벗어나면 1.0 미만 (포화 방지)")
    void voiceRangeFit_fullyInsideButOffCenter_belowOne() {
        // given: C major root=60. 사용자 50~80 → reachability=1.0, center=65, sigma=15, dist=5
        // → centeredness = exp(-0.5*(5/15)^2) ≈ 0.94596 (가우시안 감쇠 — #1639)
        // when
        final double fit = RecommendationScorer.voiceRangeFit(MusicalKey.C_MAJOR, 50, 80);
        // then: reachability 포화(1.0)에도 centeredness 가 변별력을 유지 → 1.0 미만
        assertThat(fit).isCloseTo(Math.exp(-0.5 * Math.pow(5.0 / 15.0, 2)), offset(1e-9));
        assertThat(fit).isLessThan(1.0);
    }

    @Test
    @DisplayName("voiceRangeFit (#1452): 같은 음역폭이라도 곡 키 중심이 음역 중앙에 가까운 곡이 더 높은 fit")
    void voiceRangeFit_higherForKeyNearerUserCenter() {
        // given: 사용자 56~64 (center=60). C major(root=60)=중앙, D major(root=62)=중앙에서 +2
        // when
        final double centered = RecommendationScorer.voiceRangeFit(MusicalKey.C_MAJOR, 56, 64);
        final double offCenter = RecommendationScorer.voiceRangeFit(MusicalKey.D_MAJOR, 56, 64);
        // then: 음역폭이 같아도 키 중심이 사용자 음역 중앙에 가까운 C major 가 더 높다 → 음역대 변별력
        assertThat(centered).isGreaterThan(offCenter);
    }

    @Test
    @DisplayName("voiceRangeFit (#1639): 곡 음역과 사용자 음역이 전혀 겹치지 않으면 0 이 아니라 gap 거리 소프트 감쇠로 작은 양수")
    void voiceRangeFit_noOverlap_returnsSmallPositive() {
        // given: C major 53~67, 사용자 100~119 (멀리 벗어남 — disjoint)
        // when
        final double fit = RecommendationScorer.voiceRangeFit(MusicalKey.C_MAJOR, 100, 119);
        // then: hard-zero 가 아니라 gap 거리 기반 작은 양수 (먼 곡일수록 0 에 수렴하되 0 은 아님 — #1639)
        assertThat(fit).isGreaterThan(0.0);
        assertThat(fit).isLessThan(0.01);
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
    @DisplayName("moodMatch (#1485): 정확히 일치 1.0, 미입력·곡 mood 부재 0.0, 그 외 연속 유사도(0~1)")
    void moodMatch_cases() {
        // 정확 일치 = 1.0 (기존 회귀 가드 유지)
        assertThat(RecommendationScorer.moodMatch(Mood.UPBEAT, Mood.UPBEAT)).isEqualTo(1.0);
        // 미입력/곡 mood 부재 = 0.0 (신호 없음)
        assertThat(RecommendationScorer.moodMatch(Mood.UPBEAT, null)).isEqualTo(0.0);
        assertThat(RecommendationScorer.moodMatch(null, Mood.UPBEAT)).isEqualTo(0.0);
        // 불일치는 더 이상 0.0 이 아니라 연속 유사도 — 가까운 분위기일수록 높다 (변별력).
        final double upbeatGroovy = RecommendationScorer.moodMatch(Mood.GROOVY, Mood.UPBEAT);
        final double upbeatEmotional = RecommendationScorer.moodMatch(Mood.EMOTIONAL, Mood.UPBEAT);
        assertThat(upbeatGroovy).isStrictlyBetween(0.0, 1.0);
        assertThat(upbeatEmotional).isStrictlyBetween(0.0, 1.0);
        assertThat(upbeatGroovy).isGreaterThan(upbeatEmotional);
    }

    @Test
    @DisplayName("moodSimilarity (#1485): 대칭이며 [0,1] 범위 — EMOTIONAL은 NOSTALGIC > CALM > UPBEAT 순으로 유사")
    void moodSimilarity_gradient() {
        // 대칭성
        assertThat(RecommendationScorer.moodSimilarity(Mood.CALM, Mood.EMOTIONAL))
                .isEqualTo(RecommendationScorer.moodSimilarity(Mood.EMOTIONAL, Mood.CALM));
        // 슬픈 발라드(EMOTIONAL) 요청 기준 가까운 분위기 gradient
        final double toNostalgic = RecommendationScorer.moodSimilarity(Mood.NOSTALGIC, Mood.EMOTIONAL);
        final double toCalm = RecommendationScorer.moodSimilarity(Mood.CALM, Mood.EMOTIONAL);
        final double toUpbeat = RecommendationScorer.moodSimilarity(Mood.UPBEAT, Mood.EMOTIONAL);
        assertThat(toNostalgic).isGreaterThan(toCalm);
        assertThat(toCalm).isGreaterThan(toUpbeat);
        assertThat(toUpbeat).isStrictlyBetween(0.0, 1.0);
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
    @DisplayName("score: 음역 완전 일치(중앙) + mood 일치 + tempo 완전 일치 시 voiceFit*0.5 + mood*0.2 + popularity*0.1 + tempo*0.1 정확 = 0.9 (jitter=0)")
    void score_perfectMatch_returnsExpected() {
        // given: jitter=0 으로 가중 합산 정확값 검증. C major root=60 을 음역 중앙(53~67)에 두어 rangeFit=1.0
        final Song song = buildSong(MusicalKey.C_MAJOR, Mood.UPBEAT, 128);
        final RecommendationProperties propsNoJitter = new RecommendationProperties(
                new RecommendationProperties.Weights(0.5, 0.2, 0.2, 0.1, 0.1, 0.0),
                DEFAULT_DIVERSITY, defaultTempo(), defaultGeneration(), 10, 0.0,
                RecommendationProperties.SeedStrategy.DERIVED);
        // when: 음역 53~67 (root 중앙) + UPBEAT mood + preferredBpm=128 → tempoMatch=1.0
        final RecommendationScorer.Scored scored = scorer(propsNoJitter)
                .score(song, 53, 67, Mood.UPBEAT, 128, null, new Random(42));
        // then: 0.5*1.0 + 0.2*0 + 0.2*1.0 + 0.1*1.0 + 0.1*1.0 = 0.9 (1 ULP 수준 부동소수점 허용)
        assertThat(scored.voiceRangeFit()).isEqualTo(1.0);
        assertThat(scored.moodMatch()).isEqualTo(1.0);
        assertThat(scored.tempoMatch()).isEqualTo(1.0);
        assertThat(scored.total()).isCloseTo(0.9, offset(1e-9));
    }

    @Test
    @DisplayName("score: breakdown 6신호가 모두 [0,1] 범위 안에 있다 (raw 신호 보존)")
    void score_breakdownAllFieldsInUnitInterval() {
        final Song song = buildSong(MusicalKey.C_MAJOR, Mood.UPBEAT, 120);
        final RecommendationScorer.Scored scored = scorer(defaultProperties())
                .score(song, 50, 80, Mood.UPBEAT, 120, null, new Random(0));
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
                .score(song, 50, 80, Mood.UPBEAT, 120, null, new Random(0));
        assertThat(scored.breakdown().keyMatch()).isEqualTo(1.0);
        assertThat(scored.breakdown().genreMatch()).isEqualTo(0.0);
        assertThat(scored.breakdown().popularity()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("score: UNKNOWN 키는 keyMatch=0.5 (중립)")
    void score_breakdownUnknownKey() {
        final Song song = buildSong(MusicalKey.UNKNOWN, Mood.UPBEAT, 120);
        final RecommendationScorer.Scored scored = scorer(defaultProperties())
                .score(song, 50, 80, Mood.UPBEAT, 120, null, new Random(0));
        assertThat(scored.breakdown().keyMatch()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("matchReason: 음역+분위기 모두 일치하면 통합 메시지")
    void toMatchReason_bothMatch() {
        final Song song = buildSong(MusicalKey.C_MAJOR, Mood.UPBEAT, 120);
        // total=0.7, breakdown: rangeFit=1.0, moodMatch=1.0, tempoMatch=1.0
        final ScoreBreakdown breakdown = new ScoreBreakdown(1.0, 1.0, 0.0, 1.0, 1.0, 1.0, 0.0);
        final RecommendationScorer.Scored scored = new RecommendationScorer.Scored(0.7, breakdown);
        assertThat(scored.toMatchReason(song, Mood.UPBEAT)).contains("음역대").contains("분위기");
    }

    @Test
    @DisplayName("matchReason (회귀 가드): rangeFit=0.7 임계 inclusive + moodMatch 미달 → 음역 단독 메시지")
    void toMatchReason_rangeOnly_atBoundary() {
        // given: rangeFit=0.7 임계 inclusive, moodMatch=0.0 (분기 2)
        final Song song = buildSong(MusicalKey.C_MAJOR, Mood.UPBEAT, 120);
        final ScoreBreakdown breakdown = new ScoreBreakdown(1.0, 0.7, 0.0, 0.0, 1.0, 0.5, 0.0);
        final RecommendationScorer.Scored scored = new RecommendationScorer.Scored(0.5, breakdown);
        // when
        final String reason = scored.toMatchReason(song, Mood.UPBEAT);
        // then: rangeFit 메시지만 노출, 분위기/통합 문구 없음
        assertThat(reason).isEqualTo("원곡 키가 사용자 음역대에 잘 맞음");
    }

    @Test
    @DisplayName("matchReason (회귀 가드): rangeFit=0.69 임계 바로 아래 + moodMatch=1.0 → 분위기 단독 메시지(song.mood 노출)")
    void toMatchReason_moodOnly_belowRangeBoundary() {
        // given: rangeFit=0.69 (0.7 임계 바로 아래), moodMatch=1.0 (분기 3)
        final Song song = buildSong(MusicalKey.C_MAJOR, Mood.CALM, 120);
        final ScoreBreakdown breakdown = new ScoreBreakdown(1.0, 0.69, 0.0, 1.0, 1.0, 0.5, 0.0);
        final RecommendationScorer.Scored scored = new RecommendationScorer.Scored(0.4, breakdown);
        // when: requestedMood는 UPBEAT지만 코드는 song.getMood() 사용 (라인 207L 회귀 가드)
        final String reason = scored.toMatchReason(song, Mood.UPBEAT);
        // then: song.mood인 CALM이 메시지에 노출 (requestedMood 아님)
        assertThat(reason).isEqualTo("분위기(" + Mood.CALM + ")가 요청과 일치");
        assertThat(reason).doesNotContain("음역대");
    }

    @Test
    @DisplayName("matchReason (회귀 가드): rangeFit·moodMatch 모두 임계 미달 → 폴백 메시지")
    void toMatchReason_neither_returnsFallback() {
        // given: rangeFit=0.6, moodMatch=0.0 (분기 4)
        final Song song = buildSong(MusicalKey.C_MAJOR, Mood.UPBEAT, 120);
        final ScoreBreakdown breakdown = new ScoreBreakdown(1.0, 0.6, 0.0, 0.0, 1.0, 0.5, 0.0);
        final RecommendationScorer.Scored scored = new RecommendationScorer.Scored(0.3, breakdown);
        // when
        final String reason = scored.toMatchReason(song, Mood.UPBEAT);
        // then
        assertThat(reason).isEqualTo("전반적 매칭");
    }

    @Test
    @DisplayName("matchReason (회귀 가드): bothMatch 통합 메시지는 requestedMood를 노출 (분기 1 정밀)")
    void toMatchReason_bothMatch_usesRequestedMood() {
        // given: song.mood와 requestedMood가 다른 상황에서도 moodMatch=1.0이면 분기 1 진입.
        // 통합 메시지는 코드 라인 201L에서 requestedMood를 사용하므로 회귀 가드.
        final Song song = buildSong(MusicalKey.C_MAJOR, Mood.CALM, 120);
        final ScoreBreakdown breakdown = new ScoreBreakdown(1.0, 0.7, 0.0, 1.0, 1.0, 1.0, 0.0);
        final RecommendationScorer.Scored scored = new RecommendationScorer.Scored(0.7, breakdown);
        // when
        final String reason = scored.toMatchReason(song, Mood.UPBEAT);
        // then: requestedMood(UPBEAT) 노출, song.mood(CALM)는 노출되지 않음
        assertThat(reason).contains(Mood.UPBEAT.toString());
        assertThat(reason).doesNotContain(Mood.CALM.toString());
        assertThat(reason).contains("음역대").contains("분위기");
    }

    @Test
    @DisplayName("가중치 튜닝: voiceFit 비중을 올리면 음역만 맞는 곡이 분위기만 맞는 곡보다 더 높게 나온다")
    void score_weightTuning_voiceFitDominates() {
        // given: 음역만 일치한 곡 A vs 분위기만 일치한 곡 B
        final Song voiceOnly = buildSong(MusicalKey.C_MAJOR, Mood.CALM, 120);
        final Song moodOnly = buildSong(MusicalKey.UNKNOWN, Mood.UPBEAT, 120); // voiceFit=0.5(중립)
        final RecommendationProperties voiceHeavy = new RecommendationProperties(
                new RecommendationProperties.Weights(0.8, 0.0, 0.1, 0.0, 0.0, 0.0),
                DEFAULT_DIVERSITY, defaultTempo(), defaultGeneration(), 10, 0.0,
                RecommendationProperties.SeedStrategy.DERIVED); // jitter 0 → 결정적
        // when
        final double voiceScore = scorer(voiceHeavy)
                .score(voiceOnly, 50, 80, Mood.UPBEAT, 120, null, new Random(0)).total();
        final double moodScore = scorer(voiceHeavy)
                .score(moodOnly, 50, 80, Mood.UPBEAT, 120, null, new Random(0)).total();
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
                new RecommendationProperties.Weights(0.1, 0.0, 0.8, 0.0, 0.0, 0.0),
                DEFAULT_DIVERSITY, defaultTempo(), defaultGeneration(), 10, 0.0,
                RecommendationProperties.SeedStrategy.DERIVED);
        // when
        final double voiceScore = scorer(moodHeavy)
                .score(voiceOnly, 50, 80, Mood.UPBEAT, 120, null, new Random(0)).total();
        final double moodScore = scorer(moodHeavy)
                .score(moodOnly, 50, 80, Mood.UPBEAT, 120, null, new Random(0)).total();
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
                new RecommendationProperties.Weights(0.0, 0.0, 0.0, 0.0, 1.0, 0.0),
                DEFAULT_DIVERSITY, defaultTempo(), defaultGeneration(), 10, 0.0,
                RecommendationProperties.SeedStrategy.DERIVED);
        // when: preferredBpm=130 → fast 곡이 정확 매칭, slow 곡은 distance=70 > tolerance(40) → 0.0
        final double fastScore = scorer(tempoHeavy)
                .score(fastSong, 50, 80, null, 130, null, new Random(0)).total();
        final double slowScore = scorer(tempoHeavy)
                .score(slowSong, 50, 80, null, 130, null, new Random(0)).total();
        // then
        assertThat(fastScore).isGreaterThan(slowScore);
    }

    @Test
    @DisplayName("score (경계): 모든 가중치가 0이면 total은 jitter 범위 [-jitterMagnitude, +jitterMagnitude] 안")
    void score_allWeightsZero_totalWithinJitterRange() {
        // given: 모든 가중치 0 + jitter 0.01
        final Song song = buildSong(MusicalKey.C_MAJOR, Mood.UPBEAT, 120);
        final RecommendationProperties allZero = new RecommendationProperties(
                new RecommendationProperties.Weights(0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
                DEFAULT_DIVERSITY, defaultTempo(), defaultGeneration(), 10, 0.01,
                RecommendationProperties.SeedStrategy.DERIVED);
        // when
        final RecommendationScorer.Scored scored = scorer(allZero)
                .score(song, 50, 80, Mood.UPBEAT, 120, null, new Random(42));
        // then: total은 [-0.01, 0.01] 내 (가중 합산이 0이라 jitter만 남는다)
        assertThat(scored.total()).isBetween(-0.01, 0.01);
    }

    @Test
    @DisplayName("score (경계): 모든 가중치 0 + jitter 0 이면 total 정확히 0.0")
    void score_allWeightsAndJitterZero_totalIsZero() {
        // given
        final Song song = buildSong(MusicalKey.C_MAJOR, Mood.UPBEAT, 120);
        final RecommendationProperties dead = new RecommendationProperties(
                new RecommendationProperties.Weights(0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
                DEFAULT_DIVERSITY, defaultTempo(), defaultGeneration(), 10, 0.0,
                RecommendationProperties.SeedStrategy.DERIVED);
        // when
        final RecommendationScorer.Scored scored = scorer(dead)
                .score(song, 50, 80, Mood.UPBEAT, 120, null, new Random(0));
        // then
        assertThat(scored.total()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("score (경계): voiceFit 만 1.0 가중치, 완전 매칭 곡 → total == 1.0 (jitter 0)")
    void score_onlyVoiceFitWeight_perfectMatch() {
        // given: voiceFit 1.0 단일 신호, 나머지 0
        final Song song = buildSong(MusicalKey.C_MAJOR, null, null);
        final RecommendationProperties voiceOnly = new RecommendationProperties(
                new RecommendationProperties.Weights(1.0, 0.0, 0.0, 0.0, 0.0, 0.0),
                DEFAULT_DIVERSITY, defaultTempo(), defaultGeneration(), 10, 0.0,
                RecommendationProperties.SeedStrategy.DERIVED);
        // when: 음역 53~67 (root=60 중앙, 완전 포함) → rangeFit=1.0
        final RecommendationScorer.Scored scored = scorer(voiceOnly)
                .score(song, 53, 67, null, null, null, new Random(0));
        // then: rangeFit=1.0 * 1.0 = 1.0
        assertThat(scored.total()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("score (경계): mood 만 1.0 가중치, 분위기 일치 → total == 1.0")
    void score_onlyMoodWeight_match() {
        // given
        final Song song = buildSong(MusicalKey.UNKNOWN, Mood.CALM, null);
        final RecommendationProperties moodOnly = new RecommendationProperties(
                new RecommendationProperties.Weights(0.0, 0.0, 1.0, 0.0, 0.0, 0.0),
                DEFAULT_DIVERSITY, defaultTempo(), defaultGeneration(), 10, 0.0,
                RecommendationProperties.SeedStrategy.DERIVED);
        // when
        final RecommendationScorer.Scored scored = scorer(moodOnly)
                .score(song, 50, 80, Mood.CALM, null, null, new Random(0));
        // then: moodMatch=1.0 * 1.0
        assertThat(scored.total()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("score (경계): popularity 만 1.0 가중치 → v1 popularity=1.0 고정이라 total 항상 1.0")
    void score_onlyPopularityWeight_alwaysOne() {
        // given
        final Song song = buildSong(MusicalKey.UNKNOWN, null, null);
        final RecommendationProperties popOnly = new RecommendationProperties(
                new RecommendationProperties.Weights(0.0, 0.0, 0.0, 1.0, 0.0, 0.0),
                DEFAULT_DIVERSITY, defaultTempo(), defaultGeneration(), 10, 0.0,
                RecommendationProperties.SeedStrategy.DERIVED);
        // when
        final RecommendationScorer.Scored scored = scorer(popOnly)
                .score(song, 50, 80, null, null, null, new Random(0));
        // then: popularity 신호=1.0 * 가중치 1.0
        assertThat(scored.total()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("score (경계): genre 만 1.0 가중치 → v1 genre=0.0 고정이라 total 항상 0.0")
    void score_onlyGenreWeight_alwaysZero() {
        // given
        final Song song = buildSong(MusicalKey.C_MAJOR, Mood.UPBEAT, 120);
        final RecommendationProperties genreOnly = new RecommendationProperties(
                new RecommendationProperties.Weights(0.0, 1.0, 0.0, 0.0, 0.0, 0.0),
                DEFAULT_DIVERSITY, defaultTempo(), defaultGeneration(), 10, 0.0,
                RecommendationProperties.SeedStrategy.DERIVED);
        // when
        final RecommendationScorer.Scored scored = scorer(genreOnly)
                .score(song, 50, 80, Mood.UPBEAT, 120, null, new Random(0));
        // then: genre 신호=0.0 → 가중치 무관 항상 0.0
        assertThat(scored.total()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("score (경계): 6신호 모두 최대값 + 모든 가중치 1.0 → total == 가중치 합 (popularity 신호=1, genre는 0이라 4.0)")
    void score_allSignalsMax_totalEqualsWeightSum() {
        // given: voiceFit=1, genre 신호=0(고정), mood=1, popularity=1, tempo=1 → 합 1+0+1+1+1 = 4
        final Song song = buildSong(MusicalKey.C_MAJOR, Mood.UPBEAT, 128);
        final RecommendationProperties allOnes = new RecommendationProperties(
                new RecommendationProperties.Weights(1.0, 1.0, 1.0, 1.0, 1.0, 0.0),
                DEFAULT_DIVERSITY, defaultTempo(), defaultGeneration(), 10, 0.0,
                RecommendationProperties.SeedStrategy.DERIVED);
        // when: 음역 53~67 (root 중앙) → rangeFit=1.0. UPBEAT mood + preferredBpm 128 → tempoMatch=1.0
        final RecommendationScorer.Scored scored = scorer(allOnes)
                .score(song, 53, 67, Mood.UPBEAT, 128, null, new Random(0));
        // then: 1 + 0 + 1 + 1 + 1 = 4.0
        assertThat(scored.total()).isEqualTo(4.0);
    }

    @Test
    @DisplayName("score (invariant): breakdown raw 신호값은 가중치 변경에도 동일하게 보존된다")
    void score_breakdownRawSignalsAreWeightInvariant() {
        // given: 동일 입력, 가중치만 다른 두 properties
        final Song song = buildSong(MusicalKey.C_MAJOR, Mood.UPBEAT, 128);
        final RecommendationProperties propsA = new RecommendationProperties(
                new RecommendationProperties.Weights(0.5, 0.2, 0.2, 0.1, 0.1, 0.0),
                DEFAULT_DIVERSITY, defaultTempo(), defaultGeneration(), 10, 0.0,
                RecommendationProperties.SeedStrategy.DERIVED);
        final RecommendationProperties propsB = new RecommendationProperties(
                new RecommendationProperties.Weights(0.1, 0.0, 0.9, 0.0, 0.0, 0.0),
                DEFAULT_DIVERSITY, defaultTempo(), defaultGeneration(), 10, 0.0,
                RecommendationProperties.SeedStrategy.DERIVED);
        // when
        final ScoreBreakdown breakdownA = scorer(propsA)
                .score(song, 50, 80, Mood.UPBEAT, 128, null, new Random(0)).breakdown();
        final ScoreBreakdown breakdownB = scorer(propsB)
                .score(song, 50, 80, Mood.UPBEAT, 128, null, new Random(0)).breakdown();
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
                new RecommendationProperties.Weights(0.0, 0.0, 0.0, 0.0, 1.0, 0.0),
                DEFAULT_DIVERSITY, defaultTempo(), defaultGeneration(), 10, 0.0,
                RecommendationProperties.SeedStrategy.DERIVED);
        // when
        final RecommendationScorer.Scored scored = scorer(tempoOnly)
                .score(song, 50, 80, null, null, null, new Random(0));
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
        final RecommendationScorer.Scored a = scorer(props).score(song, 50, 80, Mood.UPBEAT, 120, null, new Random(7));
        final RecommendationScorer.Scored b = scorer(props).score(song, 50, 80, Mood.UPBEAT, 120, null, new Random(7));
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

    @Test
    @DisplayName("voiceRangeFit (#1639): 사용자 음역이 곡 키 중심보다 위쪽이면 centeredness 가우시안 감쇠 → 0 아닌 양수")
    void voiceRangeFit_userAboveKeyCenter_returnsDampedPositive() {
        // given: C major root=60. 사용자 60~67 (center=63.5, sigma=3.5). dist=|60-63.5|=3.5 == sigma
        // → centeredness = exp(-0.5*(3.5/3.5)^2) = exp(-0.5) ≈ 0.6065; reachability = 7/14 = 0.5
        // when
        final double fit = RecommendationScorer.voiceRangeFit(MusicalKey.C_MAJOR, 60, 67);
        // then: hard-clip 0 이 아니라 reachability*centeredness 양수 (#1639)
        assertThat(fit).isCloseTo(0.5 * Math.exp(-0.5), offset(1e-9));
        assertThat(fit).isGreaterThan(0.0);
        assertThat(fit).isLessThan(1.0);
    }

    @Test
    @DisplayName("voiceRangeFit (#1639): 정확 경계 — 사용자 high == 곡 low → overlap 0(gap 0) → reachability=1, centeredness 만 반영")
    void voiceRangeFit_touchingBoundary_usesCenterednessOnly() {
        // given: C major 곡 53~67. 사용자 40~53 (high == 곡 low) → overlap 0, gap 0 → reachability=exp(0)=1.0
        //   centerDist=|60-46.5|=13.5, sigma=max(1,6.5)=6.5 → centeredness=exp(-0.5*(13.5/6.5)^2)
        // when
        final double fit = RecommendationScorer.voiceRangeFit(MusicalKey.C_MAJOR, 40, 53);
        // then: gap 0 이라 reachability=1.0, fit == centeredness (0 아닌 양수 — #1639)
        assertThat(fit).isCloseTo(Math.exp(-0.5 * Math.pow(13.5 / 6.5, 2)), offset(1e-9));
        assertThat(fit).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("voiceRangeFit (#1639): 사용자 음역이 곡 키 중심보다 한참 아래면 가우시안 감쇠로 0 에 근접하되 양수")
    void voiceRangeFit_userFarBelowKeyCenter_returnsTinyPositive() {
        // given: C major root=60. 사용자 40~54 (center=47, sigma=7). dist=|60-47|=13 > sigma=7
        // → centeredness = exp(-0.5*(13/7)^2) ≈ 0.178; reachability = overlap(54-53=1)/14 ≈ 0.0714
        // when
        final double fit = RecommendationScorer.voiceRangeFit(MusicalKey.C_MAJOR, 40, 54);
        // then: 멀어도 0 이 아니라 작은 양수 — 저음역 사용자 전 곡 0 사고 회귀 가드 (#1639)
        assertThat(fit).isGreaterThan(0.0);
        assertThat(fit).isLessThan(0.05);
    }

    @Test
    @DisplayName("voiceRangeFit (회귀 가드): 사용자 음역 폭 0 (low == high) → overlap 0 → 0.0")
    void voiceRangeFit_zeroUserSpan_returnsZero() {
        // given: 사용자 60~60 (점). 곡 53~67 → overlap = min(67,60) - max(53,60) = 60-60 = 0
        // when
        final double fit = RecommendationScorer.voiceRangeFit(MusicalKey.C_MAJOR, 60, 60);
        // then
        assertThat(fit).isEqualTo(0.0);
    }

    @Test
    @DisplayName("voiceRangeFit (회귀 가드): voiceLow > voiceHigh 역전 입력 → max(0, 음수) clamp → 0.0")
    void voiceRangeFit_invertedUserRange_clampsToZero() {
        // given: 잘못된 입력 사용자 low=80, high=50 (역전). 곡 53~67
        // overlap = min(67,50) - max(53,80) = 50 - 80 = -30 → Math.max(0, ...) clamp
        // when
        final double fit = RecommendationScorer.voiceRangeFit(MusicalKey.C_MAJOR, 80, 50);
        // then: NaN/음수 없이 0.0 결정적 반환
        assertThat(fit).isEqualTo(0.0);
    }

    @Test
    @DisplayName("voiceRangeFit (회귀 가드): 사용자 음역이 곡 음역에 완전 포함 — overlap == songSpan → 1.0 (clamp)")
    void voiceRangeFit_userInsideSong_returnsOne() {
        // given: C major 53~67 (span 14). 사용자 56~64 → overlap = min(67,64) - max(53,56) = 64-56 = 8
        // ratio = 8/14 ≈ 0.571 (사용자가 곡보다 좁으면 overlap == 사용자 span)
        // when
        final double fit = RecommendationScorer.voiceRangeFit(MusicalKey.C_MAJOR, 56, 64);
        // then: clamp 1.0 이하, 양수
        assertThat(fit).isEqualTo(8.0 / 14.0);
        assertThat(fit).isLessThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("voiceRangeFit (회귀 가드): LOW/HIGH_OFFSET 매핑 회귀 — A_MAJOR(root=69) 곡 음역 62~76")
    void voiceRangeFit_offsetRegressionGuard_aMajorRange() {
        // given: A_MAJOR root=69. LOW_OFFSET=-7, HIGH_OFFSET=+7 → 곡 62~76 (span 14)
        // 사용자 62~76 정확 일치 → overlap 14
        // when
        final double fit = RecommendationScorer.voiceRangeFit(MusicalKey.A_MAJOR, 62, 76);
        // then: 14/14 = 1.0. OFFSET이 변경되면 이 단언이 깨지면서 회귀 감지.
        assertThat(fit).isEqualTo(1.0);
    }

    @Test
    @DisplayName("voiceRangeFit (회귀 가드): LOW/HIGH_OFFSET 회귀 — G_MAJOR(root=67) 음역 60~74 검증")
    void voiceRangeFit_offsetRegressionGuard_gMajorRange() {
        // given: G_MAJOR root=67 → 곡 60~74. 사용자 60~74 완전 매칭
        // when
        final double fit = RecommendationScorer.voiceRangeFit(MusicalKey.G_MAJOR, 60, 74);
        // then: OFFSET ±7 가정 회귀 가드
        assertThat(fit).isEqualTo(1.0);
    }

    @Test
    @DisplayName("voiceRangeFit (회귀 가드): clamp invariant — overlap > songSpan 가능성 없음 → 항상 [0,1]")
    void voiceRangeFit_alwaysInUnitInterval() {
        // given: 여러 키/사용자 범위 조합. 어떤 입력에서도 결과는 [0,1] 안.
        // when / then
        assertThat(RecommendationScorer.voiceRangeFit(MusicalKey.C_MAJOR, 0, 127)).isBetween(0.0, 1.0);
        assertThat(RecommendationScorer.voiceRangeFit(MusicalKey.B_MAJOR, 0, 127)).isBetween(0.0, 1.0);
        assertThat(RecommendationScorer.voiceRangeFit(MusicalKey.UNKNOWN, -100, 200)).isEqualTo(0.5);
        assertThat(RecommendationScorer.voiceRangeFit(MusicalKey.E_MINOR, 50, 80)).isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("score (정확 합산): 비대칭 가중치 0.4/0.0/0.3/0.2/0.1 + 모든 신호 1.0 (jitter=0) → total 정확히 1.0")
    void score_asymmetricWeights_exactWeightedSum() {
        // given: w=(0.4, 0.0, 0.3, 0.2, 0.1). 합 1.0. 신호값 voiceFit=1, genre=0(고정), mood=1, popularity=1, tempo=1
        final Song song = buildSong(MusicalKey.C_MAJOR, Mood.UPBEAT, 128);
        final RecommendationProperties asymmetric = new RecommendationProperties(
                new RecommendationProperties.Weights(0.4, 0.0, 0.3, 0.2, 0.1, 0.0),
                DEFAULT_DIVERSITY, defaultTempo(), defaultGeneration(), 10, 0.0,
                RecommendationProperties.SeedStrategy.DERIVED);
        // when: 음역 53~67 (root 중앙) → rangeFit=1.0
        final RecommendationScorer.Scored scored = scorer(asymmetric)
                .score(song, 53, 67, Mood.UPBEAT, 128, null, new Random(0));
        // then: 0.4*1 + 0.0*0 + 0.3*1 + 0.2*1 + 0.1*1 = 1.0 (1 ULP 수준 부동소수점 허용)
        assertThat(scored.total()).isCloseTo(1.0, offset(1e-9));
    }

    @Test
    @DisplayName("score (정확 합산): 신호별 가중치 단독 곱 검증 — rangeFit(가우시안) * voiceFit 가중치 0.7 (jitter=0)")
    void score_partialSignal_exactWeightedProduct() {
        // given: voiceFit 가중치 0.7 단일, 나머지 0. C major root=60. 사용자 53~73 → reachability=1.0
        // (곡 음역 53~67 완전 포함), center=63, sigma=10, dist=3 → centeredness=exp(-0.5*(3/10)^2) ≈ 0.95600
        final Song song = buildSong(MusicalKey.C_MAJOR, null, null);
        final RecommendationProperties voiceOnly = new RecommendationProperties(
                new RecommendationProperties.Weights(0.7, 0.0, 0.0, 0.0, 0.0, 0.0),
                DEFAULT_DIVERSITY, defaultTempo(), defaultGeneration(), 10, 0.0,
                RecommendationProperties.SeedStrategy.DERIVED);
        final double expectedRangeFit = Math.exp(-0.5 * Math.pow(3.0 / 10.0, 2));
        // when
        final RecommendationScorer.Scored scored = scorer(voiceOnly)
                .score(song, 53, 73, null, null, null, new Random(0));
        // then: rangeFit(가우시안) * 가중치 0.7
        assertThat(scored.voiceRangeFit()).isCloseTo(expectedRangeFit, offset(1e-9));
        assertThat(scored.total()).isCloseTo(0.7 * expectedRangeFit, offset(1e-9));
    }

    @Test
    @DisplayName("score (정확 합산): 두 신호 동시 가중 — voiceFit 0.6 * 1.0 + mood 0.3 * 1.0 = 0.9 (jitter=0)")
    void score_twoSignals_exactWeightedSum() {
        // given: voiceFit 가중치 0.6 + mood 가중치 0.3. 나머지 0. 완전 매칭
        final Song song = buildSong(MusicalKey.C_MAJOR, Mood.UPBEAT, null);
        final RecommendationProperties twoSignals = new RecommendationProperties(
                new RecommendationProperties.Weights(0.6, 0.0, 0.3, 0.0, 0.0, 0.0),
                DEFAULT_DIVERSITY, defaultTempo(), defaultGeneration(), 10, 0.0,
                RecommendationProperties.SeedStrategy.DERIVED);
        // when: 음역 53~67 (root 중앙) → rangeFit=1.0
        final RecommendationScorer.Scored scored = scorer(twoSignals)
                .score(song, 53, 67, Mood.UPBEAT, null, null, new Random(0));
        // then: 0.6*1.0 + 0.3*1.0 = 0.9 (1 ULP 수준 부동소수점 허용)
        assertThat(scored.total()).isCloseTo(0.9, offset(1e-9));
    }

    @Test
    @DisplayName("score (결정성): seed 1234 고정 + jitter 활성 → 동일 입력 두 호출 결과 total 비트 동일")
    void score_determinism_withJitterActive() {
        // given: jitter 활성 (0.01). seed 1234 고정.
        final Song song = buildSong(MusicalKey.C_MAJOR, Mood.UPBEAT, 128);
        final RecommendationProperties withJitter = defaultProperties();
        // when: 동일 입력 + 동일 seed
        final double totalA = scorer(withJitter)
                .score(song, 50, 80, Mood.UPBEAT, 128, null, new Random(1234)).total();
        final double totalB = scorer(withJitter)
                .score(song, 50, 80, Mood.UPBEAT, 128, null, new Random(1234)).total();
        // then: 비트 동일 (jitter 흔들림이 있어도 seed가 같으면 결정적)
        assertThat(totalA).isEqualTo(totalB);
    }

    @Test
    @DisplayName("score (결정성): 다른 seed 는 다른 jitter 를 산출 — 가중 합산 동일이라도 total 은 jitter 만큼 차이")
    void score_differentSeeds_produceDifferentJitter() {
        // given: 동일 입력, 가중치 0 (가중 합산 0) + jitter 0.01 → total = jitter 그 자체
        final Song song = buildSong(MusicalKey.C_MAJOR, Mood.UPBEAT, 128);
        final RecommendationProperties jitterOnly = new RecommendationProperties(
                new RecommendationProperties.Weights(0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
                DEFAULT_DIVERSITY, defaultTempo(), defaultGeneration(), 10, 0.01,
                RecommendationProperties.SeedStrategy.DERIVED);
        // when
        final double totalSeed1 = scorer(jitterOnly)
                .score(song, 50, 80, Mood.UPBEAT, 128, null, new Random(1)).total();
        final double totalSeed2 = scorer(jitterOnly)
                .score(song, 50, 80, Mood.UPBEAT, 128, null, new Random(2)).total();
        // then: 서로 다른 jitter 값 + 둘 다 [-0.01, 0.01] 범위 내
        assertThat(totalSeed1).isNotEqualTo(totalSeed2);
        assertThat(totalSeed1).isBetween(-0.01, 0.01);
        assertThat(totalSeed2).isBetween(-0.01, 0.01);
    }

    @Test
    @DisplayName("score (결정성): 동일 seed + 다른 곡/입력 매번 → 각 케이스가 호출 간 동일 (반복 호출 안정성)")
    void score_determinism_acrossDifferentInputs() {
        // given: 두 가지 케이스. 각각 동일 seed 로 두 번 호출.
        final Song songA = buildSong(MusicalKey.C_MAJOR, Mood.UPBEAT, 120);
        final Song songB = buildSong(MusicalKey.G_MAJOR, Mood.CALM, 80);
        final RecommendationProperties props = defaultProperties();
        // when: 케이스 A 두 번
        final RecommendationScorer.Scored a1 = scorer(props).score(songA, 50, 80, Mood.UPBEAT, 120, null, new Random(
                99));
        final RecommendationScorer.Scored a2 = scorer(props).score(songA, 50, 80, Mood.UPBEAT, 120, null, new Random(
                99));
        // 케이스 B 두 번
        final RecommendationScorer.Scored b1 = scorer(props).score(songB, 60, 74, Mood.CALM, 70, null, new Random(99));
        final RecommendationScorer.Scored b2 = scorer(props).score(songB, 60, 74, Mood.CALM, 70, null, new Random(99));
        // then: 각 케이스는 호출 간 비트 동일. 케이스 간에는 서로 달라야 함 (다른 입력이면 다른 결과).
        assertThat(a1.total()).isEqualTo(a2.total());
        assertThat(a1.breakdown()).isEqualTo(a2.breakdown());
        assertThat(b1.total()).isEqualTo(b2.total());
        assertThat(b1.breakdown()).isEqualTo(b2.breakdown());
        assertThat(a1.total()).isNotEqualTo(b1.total());
    }

    @Test
    @DisplayName("score (jitter 범위): jitter 활성 + 동일 가중 합산 베이스 → 100회 반복 모두 base ± jitterMagnitude 범위")
    void score_jitterRange_alwaysWithinBound() {
        // given: voiceFit 가중치 1.0, 완전 매칭 → 가중 합산 베이스 1.0. jitter 0.01.
        final Song song = buildSong(MusicalKey.C_MAJOR, null, null);
        final RecommendationProperties props = new RecommendationProperties(
                new RecommendationProperties.Weights(1.0, 0.0, 0.0, 0.0, 0.0, 0.0),
                DEFAULT_DIVERSITY, defaultTempo(), defaultGeneration(), 10, 0.01,
                RecommendationProperties.SeedStrategy.DERIVED);
        // when / then: 음역 53~67 (root 중앙) → 베이스 1.0. 100개 seed 반복. 모든 결과는 [0.99, 1.01] 안.
        for (int seed = 0; seed < 100; seed++) {
            final double total = scorer(props).score(song, 53, 67, null, null, null, new Random(seed)).total();
            assertThat(total).as("seed=%d", seed).isBetween(0.99, 1.01);
        }
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

    private static Song buildSongWithYear(final Integer releaseYear) {
        return Song.builder()
                .title("t").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .mood(Mood.UPBEAT)
                .releaseYear(releaseYear)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
    }

    @Test
    @DisplayName("generationFit (#1487): 곡 발매연도 == 대표 시기면 1.0 (정확 매칭)")
    void generationFit_exactMatch_returnsOne() {
        // TWENTIES 대표 시기 = 2015 (defaultGeneration)
        assertThat(RecommendationScorer.generationFit(2015, AgeGroup.TWENTIES, defaultGeneration()))
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("generationFit (#1487): 거리 == tolerance(15) 이면 0.0, 절반이면 0.5 (선형 감쇠)")
    void generationFit_linearDecay() {
        // TWENTIES=2015, tolerance=15
        assertThat(RecommendationScorer.generationFit(2000, AgeGroup.TWENTIES, defaultGeneration())).isEqualTo(0.0);
        assertThat(RecommendationScorer.generationFit(2030, AgeGroup.TWENTIES, defaultGeneration())).isEqualTo(0.0);
        assertThat(RecommendationScorer.generationFit(2022, AgeGroup.TWENTIES, defaultGeneration()))
                .isCloseTo(1.0 - 7.0 / 15.0, offset(1e-9));
    }

    @Test
    @DisplayName("generationFit (#1487): tolerance 초과는 clamp 0.0")
    void generationFit_beyondTolerance_clampedToZero() {
        assertThat(RecommendationScorer.generationFit(1980, AgeGroup.TWENTIES, defaultGeneration())).isEqualTo(0.0);
    }

    @Test
    @DisplayName("generationFit (#1487): ageGroup null 이면 0.0 (가중 없음, 하위호환)")
    void generationFit_nullAgeGroup_returnsZero() {
        assertThat(RecommendationScorer.generationFit(2015, null, defaultGeneration())).isEqualTo(0.0);
    }

    @Test
    @DisplayName("generationFit (#1487): 곡 발매연도 null 이면 0.0 (정보 없음)")
    void generationFit_nullReleaseYear_returnsZero() {
        assertThat(RecommendationScorer.generationFit(null, AgeGroup.TWENTIES, defaultGeneration())).isEqualTo(0.0);
    }

    @Test
    @DisplayName("generationFit (#1487): 표에 없는 연령대면 0.0")
    void generationFit_unknownAgeGroup_returnsZero() {
        final RecommendationProperties.Generation emptyTable = new RecommendationProperties.Generation(15.0,
                java.util.Map.of());
        assertThat(RecommendationScorer.generationFit(2015, AgeGroup.TWENTIES, emptyTable)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("score (#1487): ageGroup 입력 시 대표 시기에 가까운 곡이 더 높은 total (랭킹 반영)")
    void score_ageGroupAffectsRanking() {
        // given: generation 가중만 1.0, 나머지 0 → generationFit 단독 비교. jitter=0.
        final RecommendationProperties generationOnly = new RecommendationProperties(
                new RecommendationProperties.Weights(0.0, 0.0, 0.0, 0.0, 0.0, 1.0),
                DEFAULT_DIVERSITY, defaultTempo(), defaultGeneration(), 10, 0.0,
                RecommendationProperties.SeedStrategy.DERIVED);
        final Song nearSong = buildSongWithYear(2015); // TWENTIES 대표 시기
        final Song farSong = buildSongWithYear(2000);
        // when
        final double near = scorer(generationOnly)
                .score(nearSong, 50, 80, Mood.UPBEAT, 120, AgeGroup.TWENTIES, new Random(0)).total();
        final double far = scorer(generationOnly)
                .score(farSong, 50, 80, Mood.UPBEAT, 120, AgeGroup.TWENTIES, new Random(0)).total();
        // then: 대표 시기에 가까운 곡이 더 높다
        assertThat(near).isGreaterThan(far);
    }

    @Test
    @DisplayName("voiceRangeFit (#1632): 곡 실측 음역(lowMidi/highMidi) 둘 다 있으면 키 root±7 휴리스틱 대신 실측 band 사용")
    void voiceRangeFit_actualBandOverridesKeyHeuristic() {
        // given: A_MINOR(root=69) 휴리스틱 음역 62~76 vs 실측 64~76 (band 위쪽 치우침 — 예: id=8 Eight 패턴)
        // 사용자 60~76 (center=68): 휴리스틱은 (62,76,songCenter=69) / 실측은 (64,76,songCenter=70)
        // 두 산식이 같은 입력에서도 다른 값을 산출해야 fix 가 실측 band 를 실제로 반영함을 검증.
        final double withBand = RecommendationScorer.voiceRangeFit(64, 76, MusicalKey.A_MINOR, 60, 76);
        final double withoutBand = RecommendationScorer.voiceRangeFit(null, null, MusicalKey.A_MINOR, 60, 76);
        // 휴리스틱: songSpan=14, overlap=14, reachability=1.0; centerDist=|69-68|=1, sigma=8 → cent=exp(-0.5*(1/8)^2)
        // 실측: songSpan=12, overlap=12, reachability=1.0; centerDist=|70-68|=2, sigma=8 → cent=exp(-0.5*(2/8)^2)
        assertThat(withBand).isNotEqualTo(withoutBand);
        assertThat(withoutBand).isCloseTo(Math.exp(-0.5 * Math.pow(1.0 / 8.0, 2)), offset(1e-9));
        assertThat(withBand).isCloseTo(Math.exp(-0.5 * Math.pow(2.0 / 8.0, 2)), offset(1e-9));
    }

    @Test
    @DisplayName("voiceRangeFit (#1632): lowMidi/highMidi 한쪽이라도 null 이면 키 root±7 휴리스틱 폴백 (하위호환)")
    void voiceRangeFit_fallsBackToKeyHeuristicWhenBandMissing() {
        // given: 미적재 곡 시나리오. C_MAJOR root=60 + 사용자 53~67 (정중앙 + 완전 포함 = 휴리스틱 fit=1.0)
        // when
        final double bothNull = RecommendationScorer.voiceRangeFit(null, null, MusicalKey.C_MAJOR, 53, 67);
        final double lowOnly = RecommendationScorer.voiceRangeFit(53, null, MusicalKey.C_MAJOR, 53, 67);
        final double highOnly = RecommendationScorer.voiceRangeFit(null, 67, MusicalKey.C_MAJOR, 53, 67);
        // then: 셋 다 키 휴리스틱 폴백 → 정확히 1.0
        assertThat(bothNull).isEqualTo(1.0);
        assertThat(lowOnly).isEqualTo(1.0);
        assertThat(highOnly).isEqualTo(1.0);
    }

    @Test
    @DisplayName("voiceRangeFit (#1632): 실측 band 가 사용자 음역 중앙에 가까운 곡이 멀리 떨어진 곡보다 fit 가 높다 — 음역대별 변별력")
    void voiceRangeFit_bandCloserToUserCenterScoresHigher() {
        // given: 사용자 음역 60~72 (center=66). 두 실측 band 곡:
        //  - near band: 60~74 (center=67) → 사용자 중앙과 매우 가까움
        //  - far  band: 48~62 (center=55) → 사용자 중앙에서 멀음
        final double near = RecommendationScorer.voiceRangeFit(60, 74, MusicalKey.C_MAJOR, 60, 72);
        final double far = RecommendationScorer.voiceRangeFit(48, 62, MusicalKey.C_MAJOR, 60, 72);
        // then: 사용자 음역 중앙에 가까운 band 가 더 높은 fit → 음역대 변별력 (사고 #1632)
        assertThat(near).isGreaterThan(far);
    }

    @Test
    @DisplayName("voiceRangeFit (#1632): 사용자 음역 변화에 따라 같은 실측 band 의 fit 가 달라진다 — '음역과 무관' 사고 회귀 가드")
    void voiceRangeFit_actualBandRespondsToUserRangeChanges() {
        // given: 실측 band 60~73 (B_MINOR 키 곡 패턴)
        final double narrowLowUser = RecommendationScorer.voiceRangeFit(60, 73, MusicalKey.B_MINOR, 40, 55);
        final double matchedUser = RecommendationScorer.voiceRangeFit(60, 73, MusicalKey.B_MINOR, 60, 73);
        // then: 음역 일치 사용자가 좁은 저음역 사용자보다 fit 가 명확히 높음
        assertThat(matchedUser).isGreaterThan(narrowLowUser);
        // 사용자 40~55, 곡 60~73 → disjoint 이지만 hard-zero 가 아니라 작은 양수 (저음역 변별 — #1639)
        assertThat(narrowLowUser).isGreaterThan(0.0);
        assertThat(narrowLowUser).isLessThan(0.1);
    }

    @Test
    @DisplayName("score (#1632): 실측 band 적재 곡과 미적재 곡은 같은 사용자 음역에서 voiceRangeFit 신호가 다르다 (랭킹 신호 정확도)")
    void score_actualBandFlowsThroughBreakdown() {
        // given: 사용자 음역 53~70. 같은 키 C_MAJOR 인 두 곡:
        //  - 실측 band 적재 곡: lowMidi=53, highMidi=70 (사용자 음역과 완전 일치)
        //  - 미적재 곡: lowMidi=null, highMidi=null → 키 root±7 휴리스틱(53~67)
        final Song bandSong = Song.builder()
                .title("band").artist("a")
                .keyOriginal(MusicalKey.C_MAJOR)
                .mood(Mood.UPBEAT).bpm(120)
                .lowMidi(53).highMidi(70)
                .metadataSource(MetadataSource.AUDIO_ANALYSIS)
                .build();
        final Song heuristicSong = buildSong(MusicalKey.C_MAJOR, Mood.UPBEAT, 120);
        final RecommendationProperties noJitter = new RecommendationProperties(
                new RecommendationProperties.Weights(0.5, 0.2, 0.2, 0.1, 0.1, 0.0),
                DEFAULT_DIVERSITY, defaultTempo(), defaultGeneration(), 10, 0.0,
                RecommendationProperties.SeedStrategy.DERIVED);
        // when: 같은 사용자 음역 53~70 + 같은 다른 입력
        final double bandFit = scorer(noJitter)
                .score(bandSong, 53, 70, Mood.UPBEAT, 120, null, new Random(0)).voiceRangeFit();
        final double heuristicFit = scorer(noJitter)
                .score(heuristicSong, 53, 70, Mood.UPBEAT, 120, null, new Random(0)).voiceRangeFit();
        // then: 실측 band 적재 곡의 신호값이 휴리스틱과 다름 — breakdown 흐름 검증
        assertThat(bandFit).isNotEqualTo(heuristicFit);
    }

    @Test
    @DisplayName("voiceRangeFit (#1639): 저음역 사용자(40~52)는 고음역 편중 카탈로그 전 곡과 disjoint 여도 voiceFit>0 + 곡별 변별")
    void voiceRangeFit_lowBandUser_allSongsPositiveAndDiscriminated() {
        // given: 저음역 사용자 40~52. 카탈로그는 고음역 편중(MIDI 55~78). 모든 곡이 disjoint.
        final double lowSong = RecommendationScorer.voiceRangeFit(55, 67, MusicalKey.C_MAJOR, 40, 52);
        final double midSong = RecommendationScorer.voiceRangeFit(60, 72, MusicalKey.C_MAJOR, 40, 52);
        final double highSong = RecommendationScorer.voiceRangeFit(64, 76, MusicalKey.C_MAJOR, 40, 52);
        final double veryHighSong = RecommendationScorer.voiceRangeFit(67, 78, MusicalKey.C_MAJOR, 40, 52);
        // then: hard-zero 가 아니라 모두 양수 (전 곡 0 → 변별 불가 사고 회귀 가드 — #1639)
        assertThat(lowSong).isGreaterThan(0.0);
        assertThat(midSong).isGreaterThan(0.0);
        assertThat(highSong).isGreaterThan(0.0);
        assertThat(veryHighSong).isGreaterThan(0.0);
        // 가까운 곡일수록 큰 값 → 곡별 변별 (gap 이 작을수록 reachability 큼)
        assertThat(lowSong).isGreaterThan(midSong);
        assertThat(midSong).isGreaterThan(highSong);
        assertThat(highSong).isGreaterThan(veryHighSong);
    }

    @Test
    @DisplayName("voiceRangeFit (#1639): 중음역 사용자(55~59)도 곡별 voiceFit>0 + 변별 (고음역 정상 동작은 별도 유지)")
    void voiceRangeFit_midBandUser_allSongsPositiveAndDiscriminated() {
        // given: 중음역 사용자 55~59 (center 57). 카탈로그 고음역 편중.
        final double lowSong = RecommendationScorer.voiceRangeFit(55, 67, MusicalKey.C_MAJOR, 55, 59);
        final double midSong = RecommendationScorer.voiceRangeFit(60, 72, MusicalKey.C_MAJOR, 55, 59);
        final double highSong = RecommendationScorer.voiceRangeFit(64, 76, MusicalKey.C_MAJOR, 55, 59);
        // then: 모두 양수 + 가까운 band 가 높음 → 변별 (#1639)
        assertThat(lowSong).isGreaterThan(0.0);
        assertThat(midSong).isGreaterThan(0.0);
        assertThat(highSong).isGreaterThan(0.0);
        assertThat(lowSong).isGreaterThan(midSong);
        assertThat(midSong).isGreaterThan(highSong);
    }

    @Test
    @DisplayName("voiceRangeFit (#1639): 고음역 사용자(64~76)는 겹치는 곡에서 기존대로 높은 voiceFit 유지 (정상 동작 회귀 가드)")
    void voiceRangeFit_highBandUser_overlappingSongsRemainHigh() {
        // given: 고음역 사용자 64~76. 카탈로그 고음역과 겹침.
        final double matched = RecommendationScorer.voiceRangeFit(64, 76, MusicalKey.C_MAJOR, 64, 76);
        final double midSong = RecommendationScorer.voiceRangeFit(60, 72, MusicalKey.C_MAJOR, 64, 76);
        // then: 완전 일치 곡은 1.0, 겹치는 곡도 의미 있는 양수 → 고음역 정상 동작 유지
        assertThat(matched).isEqualTo(1.0);
        assertThat(midSong).isGreaterThan(0.4);
        assertThat(matched).isGreaterThan(midSong);
    }

    @Test
    @DisplayName("voiceRangeFit (#1639): reachability gap 감쇠 단조성 — gap 이 클수록 disjoint reachability 가 작아진다")
    void voiceRangeFit_reachabilityMonotonicallyDecaysWithGap() {
        // given: 사용자 40~52 고정. 곡 band 를 점점 멀리 (gap 증가) → fit 단조 감소 확인 (소프트 감쇠 단조성)
        final double gapSmall = RecommendationScorer.voiceRangeFit(53, 65, MusicalKey.C_MAJOR, 40, 52);
        final double gapLarge = RecommendationScorer.voiceRangeFit(70, 82, MusicalKey.C_MAJOR, 40, 52);
        // then: 더 가까운 band 가 더 높은 fit
        assertThat(gapSmall).isGreaterThan(gapLarge);
        assertThat(gapLarge).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("score (#1632): UNKNOWN 키 곡도 실측 band 가 있으면 voiceFit 가 0.5 중립이 아니라 band 산식 결과 (정확도 향상)")
    void score_unknownKeyWithActualBandUsesBandNotNeutral() {
        // given: UNKNOWN 키 곡이지만 audio analysis 로 lowMidi/highMidi 적재된 케이스
        final Song unknownWithBand = Song.builder()
                .title("u").artist("a")
                .keyOriginal(MusicalKey.UNKNOWN)
                .mood(Mood.UPBEAT).bpm(120)
                .lowMidi(60).highMidi(72)
                .metadataSource(MetadataSource.AUDIO_ANALYSIS)
                .build();
        final RecommendationProperties noJitter = new RecommendationProperties(
                new RecommendationProperties.Weights(0.5, 0.2, 0.2, 0.1, 0.1, 0.0),
                DEFAULT_DIVERSITY, defaultTempo(), defaultGeneration(), 10, 0.0,
                RecommendationProperties.SeedStrategy.DERIVED);
        // when: 사용자 음역 60~72 (band 와 완전 일치) → band 산식: reachability=1.0, centeredness=1.0 → fit=1.0
        final double fit = scorer(noJitter)
                .score(unknownWithBand, 60, 72, Mood.UPBEAT, 120, null, new Random(0)).voiceRangeFit();
        // then: 중립 0.5 아닌 실측 band 산식 결과
        assertThat(fit).isEqualTo(1.0);
    }

    @Test
    @DisplayName("score (#1487): ageGroup null 이면 generationFit=0 → 랭킹 무영향(하위호환)")
    void score_nullAgeGroup_noGenerationImpact() {
        final RecommendationProperties generationOnly = new RecommendationProperties(
                new RecommendationProperties.Weights(0.0, 0.0, 0.0, 0.0, 0.0, 1.0),
                DEFAULT_DIVERSITY, defaultTempo(), defaultGeneration(), 10, 0.0,
                RecommendationProperties.SeedStrategy.DERIVED);
        final Song nearSong = buildSongWithYear(2015);
        final Song farSong = buildSongWithYear(2000);
        // when: ageGroup=null → 두 곡 모두 generationFit=0
        final double near = scorer(generationOnly)
                .score(nearSong, 50, 80, Mood.UPBEAT, 120, null, new Random(0)).total();
        final double far = scorer(generationOnly)
                .score(farSong, 50, 80, Mood.UPBEAT, 120, null, new Random(0)).total();
        // then: generationFit 가중이 0 가산이라 동일
        assertThat(near).isEqualTo(far);
    }
}
