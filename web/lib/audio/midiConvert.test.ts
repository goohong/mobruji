import { describe, expect, it } from "vitest";

import {
  frequencyToMidi,
  frequencyToMidiInt,
  midiToNoteName,
} from "./midiConvert";

describe("frequencyToMidi", () => {
  it("440Hz → MIDI 69 (A4, 표준 조율)", () => {
    expect(frequencyToMidi(440)).toBeCloseTo(69, 6);
  });

  it("880Hz → MIDI 81 (A5, 한 옥타브 위)", () => {
    expect(frequencyToMidi(880)).toBeCloseTo(81, 6);
  });

  it("220Hz → MIDI 57 (A3, 한 옥타브 아래)", () => {
    expect(frequencyToMidi(220)).toBeCloseTo(57, 6);
  });

  it("261.63Hz ≈ MIDI 60 (C4, middle C)", () => {
    expect(frequencyToMidi(261.63)).toBeCloseTo(60, 2);
  });

  it("0 / 음수 / NaN / Infinity 입력은 NaN을 반환한다", () => {
    expect(frequencyToMidi(0)).toBeNaN();
    expect(frequencyToMidi(-100)).toBeNaN();
    expect(frequencyToMidi(Number.NaN)).toBeNaN();
    expect(frequencyToMidi(Number.POSITIVE_INFINITY)).toBeNaN();
  });
});

describe("frequencyToMidiInt", () => {
  it("정확한 표준 음에 대해 정수 MIDI를 반환한다", () => {
    expect(frequencyToMidiInt(440)).toBe(69);
    expect(frequencyToMidiInt(880)).toBe(81);
  });

  it("미세 디튠(440Hz ± 약간)도 가장 가까운 정수로 반올림한다", () => {
    // A4 + 10cent ≈ 442.54Hz, 여전히 가장 가까운 정수 MIDI는 69
    expect(frequencyToMidiInt(442.54)).toBe(69);
  });

  it("무효 입력은 null을 반환한다 (안정성 필터에서 reject)", () => {
    expect(frequencyToMidiInt(0)).toBeNull();
    expect(frequencyToMidiInt(Number.NaN)).toBeNull();
    expect(frequencyToMidiInt(-1)).toBeNull();
    expect(frequencyToMidiInt(Number.NEGATIVE_INFINITY)).toBeNull();
    expect(frequencyToMidiInt(Number.POSITIVE_INFINITY)).toBeNull();
  });

  it("261.63Hz(C4) → 60", () => {
    expect(frequencyToMidiInt(261.63)).toBe(60);
  });

  it("정수 MIDI → Hz → frequencyToMidiInt round-trip이 idempotent하다 (octave-off 회귀 가드)", () => {
    // 백엔드가 받는 MIDI 범위([12, 119]) 안에서 저/중/고음 표본을 검증.
    // 변환 공식 회귀(octave-off, 12배수 누락 등)는 round-trip 한 번이면 잡힌다.
    for (const midi of [21, 36, 48, 60, 69, 72, 84, 96, 108]) {
      const hz = 440 * Math.pow(2, (midi - 69) / 12);
      expect(frequencyToMidiInt(hz)).toBe(midi);
    }
  });
});

describe("midiToNoteName re-export", () => {
  it("notes.ts의 midiToNoteName과 동일 동작 (단일 진실 원천 보장)", () => {
    expect(midiToNoteName(60)).toBe("C4");
    expect(midiToNoteName(69)).toBe("A4");
    expect(midiToNoteName(66)).toBe("F#4");
  });
});
