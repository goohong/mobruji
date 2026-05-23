package com.mobruji.voice.domain;

/**
 * 음역 MIDI 값 허용 범위 상수.
 *
 * <p>spec: docs/features/voice-range-input.md §3 "MIDI note number 12(C0) ~ 119(B8) 닫힌 구간".
 *
 * <p>이전에는 {@link VoiceRange} / {@link VoiceRangeSnapshot} 두 엔티티가 각각 같은 상수를 중복 보유했고,
 * {@code @Min(12) @Max(119)} 리터럴이 두 DTO에도 박혀 있었다. 단일 출처로 모아 drift 위험을 제거한다.
 *
 * <p>{@link jakarta.validation.constraints.Min}/{@link jakarta.validation.constraints.Max}는 컴파일타임 상수만
 * 인자로 받으므로, 상수는 {@code public static final int}로 노출해야 DTO 어노테이션에서 참조 가능하다.
 *
 * <p>검증 로직은 엔티티별 메시지 컨벤션(예: {@code lowestNoteMidi} vs {@code lowMidi}) 유지를 위해 본 클래스에 두지
 * 않는다 — 기존 회귀 테스트가 메시지 텍스트를 assertion 하므로 의미 변경을 피한다.
 */
public final class MidiRange {

    public static final int LOWEST_ALLOWED_MIDI = 12;

    public static final int HIGHEST_ALLOWED_MIDI = 119;

    private MidiRange() {
    }
}
