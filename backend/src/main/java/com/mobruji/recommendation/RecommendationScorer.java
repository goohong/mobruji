package com.mobruji.recommendation;

import java.util.Random;

import org.springframework.stereotype.Component;

import com.mobruji.song.Mood;
import com.mobruji.song.MusicalKey;
import com.mobruji.song.Song;

/**
 * v1 규칙 기반 점수 함수.
 *
 * <p>{@code score = w_voiceFit * voiceRangeFit + w_genre * genreMatch + w_mood * moodMatch
 *                 + w_popularity * popularityPrior + jitter}
 *
 * <ul>
 * <li>voiceRangeFit: 곡 키 추정 보컬 음역(root±7 semitones)과 사용자 음역의 overlap 비율 (0~1).</li>
 * <li>genreMatch: v1에서 입력 필드 없음 → 0 고정 (가중치만 보존).</li>
 * <li>moodMatch: 일치 1.0 / 미입력·불일치 0.0.</li>
 * <li>popularityPrior: 시드 데이터에 popularity 컬럼 없음 → 1.0 고정 (모든 곡에 동일 가산).</li>
 * <li>jitter: 동순위 분산용. seed 고정으로 결정성 유지 가능.</li>
 * </ul>
 *
 * <p>가중치는 {@link RecommendationProperties}로 외부화되어 튜닝 가능하다.
 */
@Component
public class RecommendationScorer {

    private final RecommendationProperties recommendationProperties;

    public RecommendationScorer(final RecommendationProperties recommendationProperties) {
        this.recommendationProperties = recommendationProperties;
    }

    public ScoreBreakdown score(
            final Song song,
            final int voiceRangeLow,
            final int voiceRangeHigh,
            final Mood requestedMood,
            final Random random) {
        final RecommendationProperties.Weights weights = recommendationProperties.weights();
        final double voiceRangeFit = voiceRangeFit(song.getKeyOriginal(), voiceRangeLow, voiceRangeHigh);
        final double genreMatch = genreMatch();
        final double moodMatch = moodMatch(song.getMood(), requestedMood);
        final double popularityPrior = popularityPrior(song);
        final double jitterMagnitude = recommendationProperties.jitterMagnitude();
        final double jitter = (random.nextDouble() * 2 - 1) * jitterMagnitude;
        final double total = weights.voiceFit() * voiceRangeFit
                + weights.genre() * genreMatch
                + weights.mood() * moodMatch
                + weights.popularity() * popularityPrior
                + jitter;
        return new ScoreBreakdown(total, voiceRangeFit, moodMatch);
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

    public record ScoreBreakdown(
            double total,
            double voiceRangeFit,
            double moodMatch
    ) {

        public String toMatchReason(final Song song, final Mood requestedMood) {
            if (voiceRangeFit >= 0.7 && moodMatch >= 1.0) {
                return "원곡 키가 음역대에 잘 맞고 분위기(" + requestedMood + ")도 일치";
            }
            if (voiceRangeFit >= 0.7) {
                return "원곡 키가 사용자 음역대에 잘 맞음";
            }
            if (moodMatch >= 1.0) {
                return "분위기(" + song.getMood() + ")가 요청과 일치";
            }
            return "전반적 매칭";
        }
    }
}
