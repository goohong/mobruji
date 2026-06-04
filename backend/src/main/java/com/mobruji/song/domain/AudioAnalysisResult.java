package com.mobruji.song.domain;

import java.util.Objects;

/**
 * Python audio analysis tool (`tools/audio-analysis/analyze.py`) 의 분석 산출물.
 *
 * <p>스키마는 {@code tools/audio-analysis/README.md} stdout JSON 계약과 1:1 대응한다.
 * Spec: {@code docs/features/audio-tooling-bootstrap.md} §3 — `lowMidi/highMidi/key/tempo/durationSec/confidence`.
 *
 * <p>모든 필수 필드는 {@code Objects.requireNonNull}로 검증한다(domain 계층 null 검증 일관성). MIDI/숫자 범위는
 * tool 결과 신뢰가 전제이며 호출 측에서 가드한다.
 *
 * @param lowMidi        보컬 멜로디 최저음 (MIDI note number, 5percentile)
 * @param highMidi       보컬 멜로디 최고음 (MIDI note number, 95percentile)
 * @param key            추정 키 (예: "C", "G#"). null 허용 — tool 측에서 미산출 가능
 * @param tempo          BPM (librosa 추정). null 허용
 * @param durationSec    분석 대상 clip 길이 (초)
 * @param confidence     분석 신뢰도 (0.0~1.0). 단일 곡 fallback 임계값 판정에 사용
 * @param toolingVersion tool 버전 문자열 (예: "analyze-py-0.1.0")
 */
public record AudioAnalysisResult(
        int lowMidi,
        int highMidi,
        String key,
        Double tempo,
        double durationSec,
        double confidence,
        String toolingVersion
) {

    /** 가창 음역 최저음의 물리적 하한 (C2). 이 미만 lowMidi 는 반주 저음 오검출 의심. */
    public static final int PLAUSIBLE_MIDI_FLOOR = 36;

    /** 가창 음역 최고음의 물리적 상한 (E6). 이 초과 highMidi 는 가창 음역 밖. */
    public static final int PLAUSIBLE_MIDI_CEIL = 88;

    /** "최저음" 이 이보다 높으면 (G4) 분석 오류 의심. */
    public static final int PLAUSIBLE_LOW_MAX = 67;

    /** "최고음" 이 이보다 낮으면 (E3) 분석 오류 의심. */
    public static final int PLAUSIBLE_HIGH_MIN = 52;

    /** 단4도 미만 음역폭은 멜로디로 비현실적. */
    public static final int PLAUSIBLE_SPAN_MIN = 5;

    /** 3옥타브+ 음역폭은 단일 멜로디로 비현실적 (옥타브 폴딩 의심). */
    public static final int PLAUSIBLE_SPAN_MAX = 40;

    public AudioAnalysisResult {
        Objects.requireNonNull(toolingVersion, "toolingVersion must not be null");
        if (lowMidi > highMidi) {
            throw new IllegalArgumentException(
                    "lowMidi must not exceed highMidi: lowMidi=" + lowMidi + ", highMidi=" + highMidi);
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence out of [0.0, 1.0]: " + confidence);
        }
        if (durationSec < 0.0) {
            throw new IllegalArgumentException("durationSec must not be negative: " + durationSec);
        }
    }

    /**
     * 분석된 음역(lowMidi/highMidi)이 사람 가창으로 합리적인지 판정한다 — 합리성 가드(#1725).
     *
     * <p>confidence 임계를 통과한 결과라도 반주 저음 오검출·옥타브 폴딩 같은 분석 오류로 음역이 가창 한계를
     * 벗어날 수 있다. backfill 적용 전 본 가드를 통과하지 못한 결과는 추천 풀에 진입시키지 않는다. 판정 기준은
     * Python CLI 측 가드({@code tools/audio-analysis/batch_analyze.py} {@code range_plausibility})와 동일하다.
     *
     * @return 가창적으로 합리적이면 {@code true}, 비합리적이면 {@code false}
     */
    public boolean isVocalRangePlausible() {
        if (lowMidi >= highMidi) {
            return false;
        }
        if (lowMidi < PLAUSIBLE_MIDI_FLOOR || lowMidi > PLAUSIBLE_LOW_MAX) {
            return false;
        }
        if (highMidi > PLAUSIBLE_MIDI_CEIL || highMidi < PLAUSIBLE_HIGH_MIN) {
            return false;
        }
        final int span = highMidi - lowMidi;
        return span >= PLAUSIBLE_SPAN_MIN && span <= PLAUSIBLE_SPAN_MAX;
    }
}
