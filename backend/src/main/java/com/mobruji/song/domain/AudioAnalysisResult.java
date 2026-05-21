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
}
