package com.mobruji.voice;

/**
 * 음역대 진단 출처. voice-range-input.md Q1 결정에 따라 PoC는 OCTAVE_PICK 위주.
 */
public enum VoiceRangeSourceMethod {

    SELF_REPORT,
    OCTAVE_PICK,
    MIC_MEASURE,
}
