package com.mobruji.recommendation.application;

import java.util.Random;

import org.springframework.stereotype.Component;

import com.mobruji.recommendation.domain.ScoreBreakdown;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;

import lombok.RequiredArgsConstructor;

import com.mobruji.recommendation.infrastructure.MusicalKeyMidiResolver;

/**
 * v1 규칙 기반 점수 함수.
 *
 * <p>{@code score = w_voiceFit * rangeFit + w_genre * genreMatch + w_mood * moodMatch
 *                 + w_popularity * popularityPrior + jitter}
 *
 * <ul>
 * <li>keyMatch: 곡 키 알려짐(1.0)/UNKNOWN(0.5). 가중 합산에는 들어가지 않는 메타 신호.</li>
 * <li>rangeFit: 곡 키 추정 보컬 음역(root±7 semitones)과 사용자 음역의 overlap 비율 (0~1).</li>
 * <li>genreMatch: v1에서 입력 필드 없음 → 0 고정 (가중치만 보존).</li>
 * <li>moodMatch: 일치 1.0 / 미입력·불일치 0.0.</li>
 * <li>popularityPrior: 시드 데이터에 popularity 컬럼 없음 → 1.0 고정 (모든 곡에 동일 가산).</li>
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

    private final RecommendationProperties recommendationProperties;

    public Scored score(
            final Song song,
            final int voiceRangeLow,
            final int voiceRangeHigh,
            final Mood requestedMood,
            final Random random) {
        final RecommendationProperties.Weights weights = recommendationProperties.weights();
        final double rangeFit = voiceRangeFit(song.getKeyOriginal(), voiceRangeLow, voiceRangeHigh);
        final double keyMatch = keyMatch(song.getKeyOriginal());
        final double genreMatch = genreMatch();
        final double moodMatch = moodMatch(song.getMood(), requestedMood);
        final double popularityPrior = popularityPrior(song);
        final double jitterMagnitude = recommendationProperties.jitterMagnitude();
        final double jitter = (random.nextDouble() * 2 - 1) * jitterMagnitude;
        final double total = weights.voiceFit() * rangeFit
                + weights.genre() * genreMatch
                + weights.mood() * moodMatch
                + weights.popularity() * popularityPrior
                + jitter;
        final ScoreBreakdown breakdown = new ScoreBreakdown(keyMatch, rangeFit, genreMatch, moodMatch, popularityPrior);
        return new Scored(total, breakdown);
    }

    static double voiceRangeFit(final MusicalKey keyOriginal, final int voiceLow, final int voiceHigh) {
        final int rootMidi = MusicalKeyMidiResolver.rootMidi(keyOriginal);
        if (rootMidi < 0) {
            return 0.5; // UNKNOWN key — neutral
        }
        final int songLow = rootMidi + MusicalKeyMidiResolver.LOW_OFFSET;
        final int songHigh = rootMidi + MusicalKeyMidiResolver.HIGH_OFFSET;
        final int overlap = Math.max(0, Math.min(songHigh, voiceHigh) - Math.max(songLow, voiceLow));
        final int songSpan = songHigh - songLow;
        if (songSpan <= 0) {
            return 0.0;
        }
        return Math.min(1.0, (double) overlap / songSpan);
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

    static double moodMatch(final Mood songMood, final Mood requestedMood) {
        if (requestedMood == null) {
            return 0.0;
        }
        if (songMood == null) {
            return 0.0;
        }
        return songMood == requestedMood ? 1.0 : 0.0;
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
     * 점수 계산 결과 — 가중 합산된 {@code total}과 raw 신호 분해를 함께 담는다.
     * 정렬·랭킹은 {@code total}만 사용하고, breakdown은 응답·로깅·디버깅용.
     */
    public record Scored(
            double total,
            ScoreBreakdown breakdown
    ) {

        public double voiceRangeFit() {
            return breakdown.rangeFit();
        }

        public double moodMatch() {
            return breakdown.moodMatch();
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
