package com.mobruji.recommendation.application;

import java.util.Random;

import org.springframework.stereotype.Component;

import com.mobruji.recommendation.domain.AgeGroup;
import com.mobruji.recommendation.domain.ScoreBreakdown;
import com.mobruji.recommendation.domain.TransposeSuggestion;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;

import lombok.RequiredArgsConstructor;

import com.mobruji.recommendation.infrastructure.MusicalKeyMidiResolver;

/**
 * v1/v2 규칙 기반 점수 함수.
 *
 * <p>{@code score = w_voiceFit * rangeFit + w_genre * genreMatch + w_mood * moodMatch
 *                 + w_popularity * popularityPrior + w_tempo * tempoMatch + w_generation * generationFit + jitter}
 *
 * <ul>
 * <li>keyMatch: 곡 키 알려짐(1.0)/UNKNOWN(0.5). 가중 합산에는 들어가지 않는 메타 신호.</li>
 * <li>rangeFit: {@code reachability * centeredness} (0~1). reachability=겹치면 overlap 비율, disjoint 면 gap 거리
 * 소프트 감쇠({@code exp(-gap/scale)}); centeredness=곡 중심↔사용자 음역 중앙 거리의 가우시안 감쇠. 넓은 음역에서
 * overlap 이 포화돼도 음역대별 변별력 유지(#1452). 저·중음역 사용자가 고음역 편중 카탈로그와 disjoint 여도 0 으로
 * 떨어지지 않고 곡별로 변별된다(#1639).</li>
 * <li>genreMatch: v1에서 입력 필드 없음 → 0 고정 (가중치만 보존).</li>
 * <li>moodMatch: 분위기 연속 유사도. 정확히 일치 1.0, 미입력·곡 mood 부재 0.0, 그 외 (energy,brightness) 좌표 거리 기반 유사도(#1485).</li>
 * <li>popularityPrior: 시드 데이터에 popularity 컬럼 없음 → 1.0 고정 (모든 곡에 동일 가산).</li>
 * <li>tempoMatch (v2 #218): {@code 1.0 - min(1.0, |songBpm - preferredBpm| / tolerance)}.
 * 곡 BPM이 null이거나 사용자 선호 BPM이 결정될 수 없으면 0.5(중립).</li>
 * <li>generationFit (#1487): {@code 1.0 - min(1.0, |songReleaseYear - representativeYear| / toleranceYears)}.
 * 연령대 미입력 또는 곡 발매연도 부재면 0.0(가중 없음) — 미입력 시 랭킹 영향 없음(하위호환).</li>
 * <li>jitter: 동순위 분산용. seed 고정으로 결정성 유지 가능.</li>
 * </ul>
 *
 * <p>가중치는 {@link RecommendationProperties}로 외부화되어 튜닝 가능하다.
 *
 * <p>반환값 {@link Scored}는 가중 합산된 total과 raw 신호 분해({@link ScoreBreakdown})를 함께 담아
 * "Why this song?" UX(spec #145)를 지원한다.
 */
@Component
@RequiredArgsConstructor
public class RecommendationScorer {

    /**
     * tempoMatch 신호 결정 불가(곡 BPM 또는 사용자 선호 BPM 부재) 시의 중립값.
     * popularity와 동일한 1.0 가산 대신 0.5로 두어, "정보 없음"을 가중 합산에서 명시 차별화한다.
     */
    static final double TEMPO_MATCH_NEUTRAL = 0.5;

    /**
     * 분위기 좌표(energy, brightness ∈ [0,1]) 평면에서 가능한 최대 거리 = 대각선 {@code sqrt(2)}.
     * moodSimilarity 를 [0,1] 로 정규화하는 분모.
     */
    static final double MOOD_MAX_DISTANCE = Math.sqrt(2.0);

    /**
     * 조옮김(transpose) 탐색 범위(반음). 카라오케 기기 통상 키 조절 폭(±6)에 맞춰, 이 안에서만 최적 이동량을 찾는다.
     */
    static final int MAX_TRANSPOSE_SEMITONES = 6;

    /**
     * 조옮김을 제안하는 voiceFit(rangeFit) 임계. 이 값 이상이면 원곡 그대로도 음역대에 무난하다고 보고 제안하지 않는다.
     * {@link ScoredRecommendation} 의 voiceFit 사유 분기("무난하게 맞아요" 경계)와 동일한 0.4 를 쓴다.
     */
    static final double TRANSPOSE_SUGGEST_FIT_THRESHOLD = 0.4;

    /**
     * disjoint(겹침 0) 곡의 reachability 소프트 감쇠 스케일(반음). 사용자 음역과 곡 음역 사이 gap 이 클수록
     * {@code Math.exp(-gap / GAP_SCALE)} 로 0 에 수렴하되 정확히 0 은 되지 않는다. 옥타브(12반음) 떨어지면
     * {@code 1/e≈0.368} 가 되도록 12 로 둔다. hard-zero 산식은 저·중음역 사용자에게 모든 곡 voiceFit=0 을
     * 만들어 변별을 못 했기에(#1639), gap 거리 기반 양수로 가까운 곡일수록 높은 값을 준다.
     */
    static final double REACHABILITY_GAP_SCALE = 12.0;

    /**
     * centeredness 가우시안 sigma 의 하한(반음). 사용자 음역폭이 0 에 가까워도 분모가 0 이 되지 않도록 보호하며,
     * 일반적으로는 {@code Math.max(1.0, userSpan/2.0)} 로 음역폭에 비례한다. 선형 hard-clip 은 중심 거리가
     * {@code userSpan/2} 를 넘으면 centeredness=0 → voiceFit=0 이라 변별을 못 했기에(#1639) 가우시안으로 매끄럽게 감쇠한다.
     */
    static final double CENTEREDNESS_SIGMA_FLOOR = 1.0;

    private final RecommendationProperties recommendationProperties;

    public Scored score(
            final Song song,
            final int voiceRangeLow,
            final int voiceRangeHigh,
            final Mood requestedMood,
            final Integer preferredBpm,
            final AgeGroup ageGroup,
            final Random random) {
        final RecommendationProperties.Weights weights = recommendationProperties.weights();
        final RecommendationProperties.Tempo tempo = recommendationProperties.tempo();
        final RecommendationProperties.Generation generation = recommendationProperties.generation();
        final double rangeFit = voiceRangeFit(
                song.getLowMidi(), song.getHighMidi(), song.getKeyOriginal(), voiceRangeLow, voiceRangeHigh);
        final double keyMatch = keyMatch(song.getKeyOriginal());
        final double genreMatch = genreMatch();
        final double moodMatch = moodMatch(song.getMood(), requestedMood);
        final double popularityPrior = popularityPrior(song);
        final double tempoMatch = tempoMatch(song.getBpm(), preferredBpm, requestedMood, tempo);
        final double generationFit = generationFit(song.getReleaseYear(), ageGroup, generation);
        final double jitterMagnitude = recommendationProperties.jitterMagnitude();
        final double jitter = (random.nextDouble() * 2 - 1) * jitterMagnitude;
        final double total = weights.voiceFit() * rangeFit
                + weights.genre() * genreMatch
                + weights.mood() * moodMatch
                + weights.popularity() * popularityPrior
                + weights.tempoMatch() * tempoMatch
                + weights.generation() * generationFit
                + jitter;
        final ScoreBreakdown breakdown = new ScoreBreakdown(
                keyMatch, rangeFit, genreMatch, moodMatch, popularityPrior, tempoMatch, generationFit);
        final TransposeSuggestion suggestedTranspose = suggestTranspose(
                song.getKeyOriginal(), voiceRangeLow, voiceRangeHigh);
        return new Scored(total, breakdown, suggestedTranspose);
    }

    static double voiceRangeFit(final MusicalKey keyOriginal, final int voiceLow, final int voiceHigh) {
        final int rootMidi = MusicalKeyMidiResolver.rootMidi(keyOriginal);
        if (rootMidi < 0) {
            return 0.5; // UNKNOWN key — neutral
        }
        return voiceRangeFitForRoot(rootMidi, voiceLow, voiceHigh);
    }

    /**
     * 곡 실측 음역(audio analysis 적재: {@code low_midi}/{@code high_midi}) 우선 적합도 산정.
     *
     * <p>둘 다 not-null 이면 실측 band 로 reachability/centeredness 산식을 그대로 적용한다 — 키 root±7 휴리스틱은
     * 곡 분포를 53~78 MIDI 좁은 구간으로 갇히게 만들어 사용자 음역대 변화에 따른 변별력이 약한 사고를 만들었다 (#1632).
     * 한쪽이라도 null 이면 기존 키 root±7 휴리스틱({@link #voiceRangeFit(MusicalKey, int, int)})으로 폴백해
     * 미적재 곡의 하위호환을 유지한다.
     *
     * <p>spec: {@code docs/features/recommendation-algorithm-v1.md} §6 v1+v2 (voiceRangeFit 산식) — 실측 데이터
     * 활용은 같은 산식의 입력 정확도 향상에 그쳐 가중치/결정성/SeedDeriver 입력에는 영향이 없다.
     */
    static double voiceRangeFit(
            final Integer songLowMidi,
            final Integer songHighMidi,
            final MusicalKey keyOriginal,
            final int voiceLow,
            final int voiceHigh) {
        if (songLowMidi != null && songHighMidi != null) {
            return voiceRangeFitForBand(songLowMidi, songHighMidi, voiceLow, voiceHigh);
        }
        return voiceRangeFit(keyOriginal, voiceLow, voiceHigh);
    }

    /**
     * 곡 음역 band(songLow, songHigh) 가 주어졌을 때의 음역 적합도(0~1). reachability/centeredness 산식은
     * {@link #voiceRangeFitForRoot}와 동일하며, 중심점만 root MIDI 대신 band 중심 {@code (songLow+songHigh)/2} 로
     * 잡는다. 곡 실측 음역과 휴리스틱 음역에 같은 산식을 일관 적용해 결과 해석을 단일 패턴으로 유지한다.
     */
    static double voiceRangeFitForBand(
            final int songLow, final int songHigh, final int voiceLow, final int voiceHigh) {
        final int songSpan = songHigh - songLow;
        final int userSpan = voiceHigh - voiceLow;
        if (songSpan <= 0 || userSpan <= 0) {
            return 0.0;
        }
        final double reachability = reachability(songLow, songHigh, voiceLow, voiceHigh, songSpan);
        final double songCenter = (songLow + songHigh) / 2.0;
        final double userCenter = (voiceLow + voiceHigh) / 2.0;
        final double centeredness = centeredness(Math.abs(songCenter - userCenter), userSpan);
        return reachability * centeredness;
    }

    /**
     * 키 root MIDI 가 주어졌을 때의 음역 적합도(0~1). {@link #voiceRangeFit}이 위임하며, 조옮김 탐색은
     * {@code rootMidi} 에 반음 이동량을 더한 값으로 같은 산식을 재사용해 voiceFit 과 비교 가능한 값을 얻는다.
     */
    static double voiceRangeFitForRoot(final int rootMidi, final int voiceLow, final int voiceHigh) {
        final int songLow = rootMidi + MusicalKeyMidiResolver.LOW_OFFSET;
        final int songHigh = rootMidi + MusicalKeyMidiResolver.HIGH_OFFSET;
        final int songSpan = songHigh - songLow;
        final int userSpan = voiceHigh - voiceLow;
        if (songSpan <= 0 || userSpan <= 0) {
            return 0.0;
        }
        // (1) reachability: 사용자가 곡 음역(root±7) 중 실제 닿을 수 있는 비율. overlap>0 이면 비율, disjoint 면
        // gap 거리 기반 소프트 감쇠 — 안 겹쳐도 가까운 곡은 양수라 변별이 살아난다(#1639).
        final double reachability = reachability(songLow, songHigh, voiceLow, voiceHigh, songSpan);
        // (2) centeredness: 곡 키 중심이 사용자 음역 중앙에 가까울수록 1.0, 멀수록 가우시안으로 매끄럽게 감쇠.
        // reachability 단독은 사용자 음역이 곡 음역을 완전히 포함하면(넓은 음역) 모든 곡이 1.0 으로 포화돼
        // 음역대 입력이 순위에 반영되지 않는다(#1452). centeredness 를 곱해 음역대별 변별력을 회복한다.
        final double userCenter = (voiceLow + voiceHigh) / 2.0;
        final double centeredness = centeredness(Math.abs(rootMidi - userCenter), userSpan);
        return reachability * centeredness;
    }

    /**
     * reachability(0~1): 곡 음역과 사용자 음역의 겹침 비율. overlap&gt;0 이면 {@code min(1.0, overlap/songSpan)} 그대로,
     * 겹침이 없으면(disjoint) 두 구간 최소 거리 gap 에 대해 {@code Math.exp(-gap / REACHABILITY_GAP_SCALE)} 로
     * 소프트 감쇠한다. hard-zero 가 저·중음역 사용자에게 모든 곡 voiceFit=0 을 만들던 사고(#1639)를 막고,
     * disjoint 라도 가까운 곡일수록 큰 값을 줘 곡별 변별을 유지한다.
     */
    private static double reachability(
            final int songLow, final int songHigh, final int voiceLow, final int voiceHigh, final int songSpan) {
        final int overlap = Math.min(songHigh, voiceHigh) - Math.max(songLow, voiceLow);
        if (overlap > 0) {
            return Math.min(1.0, (double) overlap / songSpan);
        }
        final int gap = -overlap;
        return Math.exp(-gap / REACHABILITY_GAP_SCALE);
    }

    /**
     * centeredness(0~1): 곡 중심과 사용자 음역 중앙의 거리 {@code centerDistance} 를 가우시안으로 환산한다.
     * {@code Math.exp(-0.5 * (centerDistance / sigma)^2)}, sigma = {@code max(CENTEREDNESS_SIGMA_FLOOR, userSpan/2)}.
     * 선형 hard-clip 은 거리가 {@code userSpan/2} 를 넘으면 0 → voiceFit=0 이라 변별을 못 했기에(#1639), 멀어도 0 이
     * 되지 않고 매끄럽게 감쇠하도록 가우시안을 쓴다.
     */
    private static double centeredness(final double centerDistance, final int userSpan) {
        final double sigma = Math.max(CENTEREDNESS_SIGMA_FLOOR, userSpan / 2.0);
        final double normalized = centerDistance / sigma;
        return Math.exp(-0.5 * normalized * normalized);
    }

    /**
     * 키 조옮김(transpose) 제안 (#1544). 원곡 키가 사용자 음역대에 부담스러운(voiceFit 낮은) 곡에 대해,
     * {@code ±MAX_TRANSPOSE_SEMITONES} 반음 안에서 적합도를 가장 끌어올리는 이동량을 찾는다.
     *
     * <p>{@code null} 을 돌려주는 경우(=제안 없음):
     * <ul>
     * <li>곡 키가 UNKNOWN — 적합도 산정 근거가 없어 어디로 옮길지 계산할 수 없음.</li>
     * <li>원곡 그대로도 voiceFit 이 {@link #TRANSPOSE_SUGGEST_FIT_THRESHOLD} 이상 — 굳이 옮길 필요 없음.</li>
     * <li>±범위 안에서 원곡보다 적합도를 높이는 이동량이 없음.</li>
     * </ul>
     *
     * <p>탐색은 이동 폭이 작은 순(|반음|=1→6)으로 돌며 더 높은 적합도일 때만 갱신한다. 따라서 같은 적합도라면
     * 더 작은 이동량을, 폭이 같으면 내림(-)을 우선해 결정적으로 한 값을 고른다.
     */
    static TransposeSuggestion suggestTranspose(
            final MusicalKey keyOriginal, final int voiceLow, final int voiceHigh) {
        final int rootMidi = MusicalKeyMidiResolver.rootMidi(keyOriginal);
        if (rootMidi < 0) {
            return null; // UNKNOWN key — 산정 근거 없음
        }
        final double originalFit = voiceRangeFitForRoot(rootMidi, voiceLow, voiceHigh);
        if (originalFit >= TRANSPOSE_SUGGEST_FIT_THRESHOLD) {
            return null; // 원곡 그대로도 무난
        }
        int bestSemitones = 0;
        double bestFit = originalFit;
        for (int magnitude = 1; magnitude <= MAX_TRANSPOSE_SEMITONES; magnitude++) {
            for (final int semitones : new int[]{-magnitude, magnitude}) {
                final double fit = voiceRangeFitForRoot(rootMidi + semitones, voiceLow, voiceHigh);
                if (fit > bestFit) {
                    bestFit = fit;
                    bestSemitones = semitones;
                }
            }
        }
        if (bestSemitones == 0) {
            return null; // 어느 방향으로도 개선되지 않음
        }
        return new TransposeSuggestion(bestSemitones, bestFit);
    }

    /**
     * 곡 키 정보가 데이터에 있는지(=설명 가능성)에 대한 메타 신호. UNKNOWN은 중립값 0.5, 그 외 알려진 키는 1.0.
     * 가중치 합산에는 포함되지 않고 사용자에게 "이 추천이 키 정보를 알고 한 것인지"를 노출하는 용도.
     */
    static double keyMatch(final MusicalKey keyOriginal) {
        if (keyOriginal == null || keyOriginal == MusicalKey.UNKNOWN) {
            return 0.5;
        }
        return 1.0;
    }

    /**
     * 분위기 적합도 신호 (0~1). 정확히 일치하면 1.0, 미입력·곡 mood 부재면 0.0, 그 외에는
     * 분위기 간 유사도({@link #moodSimilarity})를 그대로 반환한다.
     *
     * <p>v1의 이진(1.0/0.0) 매칭은 같은 mood끼리만 가산점이 같고 그 외에는 모두 0.0 이라 슬픈 발라드↔록 발라드↔댄스
     * 처럼 결이 다른 분위기 간 변별이 안 됐다(#1485). voiceRangeFit(#1454)이 연속 신호로 음역대 변별력을 살린 패턴을
     * 따라 분위기도 연속 유사도로 바꿔, 요청 분위기와 가까운 곡이 또렷이 상위로 오도록 한다.
     */
    static double moodMatch(final Mood songMood, final Mood requestedMood) {
        if (requestedMood == null || songMood == null) {
            return 0.0;
        }
        return moodSimilarity(songMood, requestedMood);
    }

    /**
     * 두 분위기의 유사도 (0~1). 각 분위기를 {@code (energy, brightness)} 2차원 좌표로 두고 유클리드 거리를
     * {@link #MOOD_MAX_DISTANCE}로 정규화해 {@code 1.0 - distance/max} 로 환산한다. 같은 분위기는 1.0,
     * 가장 먼 분위기 쌍(예: UPBEAT↔EMOTIONAL)도 0 이 아닌 양수가 나와 "결이 조금 다름"을 연속적으로 표현한다.
     *
     * <ul>
     * <li>energy — 곡의 에너지/격렬함 (잔잔 0 ~ 격렬 1)</li>
     * <li>brightness — 정서의 밝기 (어두움·슬픔 0 ~ 밝음·신남 1)</li>
     * </ul>
     */
    static double moodSimilarity(final Mood songMood, final Mood requestedMood) {
        if (songMood == requestedMood) {
            return 1.0;
        }
        final double[] songCoordinate = moodCoordinate(songMood);
        final double[] requestedCoordinate = moodCoordinate(requestedMood);
        final double energyDelta = songCoordinate[0] - requestedCoordinate[0];
        final double brightnessDelta = songCoordinate[1] - requestedCoordinate[1];
        final double distance = Math.sqrt(energyDelta * energyDelta + brightnessDelta * brightnessDelta);
        return Math.max(0.0, 1.0 - distance / MOOD_MAX_DISTANCE);
    }

    private static double[] moodCoordinate(final Mood mood) {
        return switch (mood) {
            case UPBEAT -> new double[]{1.0, 1.0};
            case GROOVY -> new double[]{0.8, 0.8};
            case POWERFUL -> new double[]{1.0, 0.5};
            case CALM -> new double[]{0.2, 0.6};
            case EMOTIONAL -> new double[]{0.4, 0.2};
            case NOSTALGIC -> new double[]{0.3, 0.3};
        };
    }

    /**
     * v1에서는 request에 genre 입력 필드가 없어 신호값을 0으로 둔다.
     * 가중치만 properties로 보존하여, 추후 Song에 장르 매칭 입력이 추가될 때 본 메서드 시그니처만 확장하면 된다.
     */
    static double genreMatch() {
        return 0.0;
    }

    /**
     * v1에서는 시드 데이터에 popularity 컬럼이 없으므로 1.0 고정.
     * 가중치는 모든 곡에 동일하게 가산되어 ranking에 영향이 없다.
     */
    static double popularityPrior(final Song song) {
        return 1.0;
    }

    /**
     * v2(#218) tempoMatch 신호.
     *
     * <p>산식: {@code 1.0 - min(1.0, |songBpm - target| / tolerance)} — target은 사용자 입력 {@code preferredBpm}
     * 이 있으면 그 값, 없으면 mood 기반 default BPM. 곡 BPM이 null이거나 target이 결정될 수 없으면 {@link #TEMPO_MATCH_NEUTRAL}
     * (정보 없음).
     *
     * <p>가중 합산에는 weights.tempoMatch로 들어가며, raw 신호는 ScoreBreakdown에 보존된다.
     */
    static double tempoMatch(
            final Integer songBpm,
            final Integer preferredBpm,
            final Mood requestedMood,
            final RecommendationProperties.Tempo tempo) {
        if (songBpm == null) {
            return TEMPO_MATCH_NEUTRAL;
        }
        final Integer target = resolveTargetBpm(preferredBpm, requestedMood, tempo);
        if (target == null) {
            return TEMPO_MATCH_NEUTRAL;
        }
        final double distance = Math.abs((double) songBpm - target);
        final double normalized = Math.min(1.0, distance / tempo.distanceTolerance());
        return 1.0 - normalized;
    }

    private static Integer resolveTargetBpm(
            final Integer preferredBpm,
            final Mood requestedMood,
            final RecommendationProperties.Tempo tempo) {
        if (preferredBpm != null) {
            return preferredBpm;
        }
        if (requestedMood != null) {
            final Integer moodDefault = tempo.moodDefaultBpm().get(requestedMood);
            if (moodDefault != null) {
                return moodDefault;
            }
        }
        return tempo.fallbackBpm();
    }

    /**
     * generationFit 신호 (#1487).
     *
     * <p>산식: {@code 1.0 - min(1.0, |songReleaseYear - representativeYear| / toleranceYears)} —
     * representativeYear는 요청 연령대의 "대표 시기" 발매연도(properties 외부화). 연령대가 null이거나, 곡 발매연도가
     * null이거나, 연령대가 표에 없으면 0.0(가중 없음) — 미입력 시 랭킹 영향 없음(하위호환).
     *
     * <p>가중 합산에는 weights.generation으로 들어가며, raw 신호는 ScoreBreakdown에 보존된다.
     */
    static double generationFit(
            final Integer releaseYear,
            final AgeGroup ageGroup,
            final RecommendationProperties.Generation generation) {
        if (ageGroup == null || releaseYear == null) {
            return 0.0;
        }
        final Integer representativeYear = generation.representativeYear().get(ageGroup);
        if (representativeYear == null) {
            return 0.0;
        }
        final double distance = Math.abs((double) releaseYear - representativeYear);
        final double normalized = Math.min(1.0, distance / generation.distanceToleranceYears());
        return 1.0 - normalized;
    }

    /**
     * 점수 계산 결과 — 가중 합산된 {@code total}과 raw 신호 분해를 함께 담는다.
     * 정렬·랭킹은 {@code total}만 사용하고, breakdown은 응답·로깅·디버깅용.
     * {@code suggestedTranspose}는 voiceFit 낮은 곡의 권장 조옮김(#1544)으로, 없으면 {@code null}.
     */
    public record Scored(
            double total,
            ScoreBreakdown breakdown,
            TransposeSuggestion suggestedTranspose
    ) {

        /**
         * 조옮김 제안이 없는 호출 편의 생성자(테스트 stub 등). {@code suggestedTranspose} 를 {@code null} 로 둔다.
         */
        public Scored(final double total, final ScoreBreakdown breakdown) {
            this(total, breakdown, null);
        }

        public double voiceRangeFit() {
            return breakdown.rangeFit();
        }

        public double moodMatch() {
            return breakdown.moodMatch();
        }

        public double tempoMatch() {
            return breakdown.tempoMatch();
        }

        /**
         * top 신호 기반 한국어 사유 문자열. fe 14가 다중 줄 펼침을 client-side로 처리 중이라 응답 호환을 위해
         * 단일 string으로 유지. breakdown이 함께 노출되므로 fe는 펼침 시 raw 신호로 다중 줄을 구성한다.
         */
        public String toMatchReason(final Song song, final Mood requestedMood) {
            final double rangeFit = breakdown.rangeFit();
            final double moodMatch = breakdown.moodMatch();
            if (rangeFit >= 0.7 && moodMatch >= 1.0) {
                return "원곡 키가 음역대에 잘 맞고 분위기(" + requestedMood + ")도 일치";
            }
            if (rangeFit >= 0.7) {
                return "원곡 키가 사용자 음역대에 잘 맞음";
            }
            if (moodMatch >= 1.0) {
                return "분위기(" + song.getMood() + ")가 요청과 일치";
            }
            return "전반적 매칭";
        }
    }
}
