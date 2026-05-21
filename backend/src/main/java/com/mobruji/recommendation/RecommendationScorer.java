package com.mobruji.recommendation;

import java.util.Random;

import com.mobruji.song.Mood;
import com.mobruji.song.MusicalKey;
import com.mobruji.song.Song;

/**
 * v1 규칙 기반 점수 함수.
 *
 * score = W_VOICE_FIT * voiceRangeFit + W_MOOD * moodMatch + jitter
 *
 * - voiceRangeFit: 곡 키의 추정 보컬 음역 중심 ± 7 semitones가 사용자 음역에 들어가는 비율.
 * - moodMatch: 분위기 일치 시 1.0, 미입력/불일치 시 0.0.
 * - genderMatch / popularityPrior는 v1 미구현 (spec 결정 로그 참조).
 * - jitter: 같은 점수 동순위 분산용. seed 고정으로 결정성 유지 가능.
 */
public final class RecommendationScorer {

    public static final double WEIGHT_VOICE_FIT = 0.5;
    public static final double WEIGHT_MOOD = 0.2;
    public static final double JITTER_MAGNITUDE = 0.01;

    private RecommendationScorer() {
    }

    public static ScoreBreakdown score(
            final Song song,
            final int voiceRangeLow,
            final int voiceRangeHigh,
            final Mood requestedMood,
            final Random random) {
        final double voiceRangeFit = voiceRangeFit(song.getKeyOriginal(), voiceRangeLow, voiceRangeHigh);
        final double moodMatch = moodMatch(song.getMood(), requestedMood);
        final double jitter = (random.nextDouble() * 2 - 1) * JITTER_MAGNITUDE;
        final double total = WEIGHT_VOICE_FIT * voiceRangeFit + WEIGHT_MOOD * moodMatch + jitter;
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
