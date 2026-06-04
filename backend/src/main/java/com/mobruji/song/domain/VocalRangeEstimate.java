package com.mobruji.song.domain;

/**
 * keyOriginal/genre 메타만으로 추정한 곡 보컬 음역대 — 오디오 자체분석 인프라 부재(#1778) 동안의 interim 산출물.
 *
 * <p>{@link VocalRangeEstimator} 가 생성하며 {@link Song#applyEstimatedVocalRange(VocalRangeEstimate)} 가 소비한다.
 * {@link AudioAnalysisResult} 가 Python tool 산출 음역대를 표현하듯, 본 record 는 메타 추정 음역대를 표현한다.
 *
 * <p>추정값은 자체분석값보다 권위가 낮다 — {@link #confidence} 를 자체분석 임계(기본 0.6) 미만으로 부여해
 * 추후 {@link Song#backfillFromAudioAnalysis(AudioAnalysisResult, double)} 가 그대로 덮어쓰게 한다.
 *
 * @param lowMidi    추정 최저음 (MIDI note number)
 * @param highMidi   추정 최고음 (MIDI note number)
 * @param confidence 추정 신뢰도 (0.0~1.0). 자체분석 권위 우선을 위해 낮게 부여한다
 */
public record VocalRangeEstimate(
        int lowMidi,
        int highMidi,
        double confidence
) {

    public VocalRangeEstimate {
        if (lowMidi > highMidi) {
            throw new IllegalArgumentException(
                    "lowMidi must not exceed highMidi: lowMidi=" + lowMidi + ", highMidi=" + highMidi);
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence out of [0.0, 1.0]: " + confidence);
        }
    }

    /**
     * 추정 음역이 사람 가창으로 합리적인지 판정한다 — 합리성 가드(#1737). 판정 기준은
     * {@link AudioAnalysisResult#isVocalRangePlausible()} 와 동일한 상수를 재사용해, 메타 추정값도 자체분석값과
     * 같은 가드를 통과해야만 추천 풀에 진입하도록 한다.
     */
    public boolean isVocalRangePlausible() {
        if (lowMidi >= highMidi) {
            return false;
        }
        if (lowMidi < AudioAnalysisResult.PLAUSIBLE_MIDI_FLOOR
                || lowMidi > AudioAnalysisResult.PLAUSIBLE_LOW_MAX) {
            return false;
        }
        if (highMidi > AudioAnalysisResult.PLAUSIBLE_MIDI_CEIL
                || highMidi < AudioAnalysisResult.PLAUSIBLE_HIGH_MIN) {
            return false;
        }
        final int span = highMidi - lowMidi;
        return span >= AudioAnalysisResult.PLAUSIBLE_SPAN_MIN
                && span <= AudioAnalysisResult.PLAUSIBLE_SPAN_MAX;
    }
}
