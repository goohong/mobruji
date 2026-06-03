/**
 * PitchWaveRing 헬퍼 결정성 테스트 — ui-ux-redesign-pr-5-pitch-wave.md §5-3 G1~G3.
 *
 * given/when/then. 순수 함수라 React 렌더 없이 알고리즘 결정성만 못 박는다.
 */

import { describe, expect, it } from "vitest";

import {
  RING_RADIUS,
  arcCircumference,
  hzToMidi,
  midiToRingCoord,
  midiToRingPercent,
  progressDashOffset,
  ringPercentToCoord,
} from "./PitchWaveRing.helpers";

describe("hzToMidi 결정성 (G1)", () => {
  it("A4(440Hz) = MIDI 69", () => {
    expect(hzToMidi(440)).toBe(69);
  });

  it("A0(27.5Hz) = MIDI 21 (정확히 4 옥타브 아래)", () => {
    expect(hzToMidi(27.5)).toBe(21);
  });

  it("C8(4186Hz) ≈ MIDI 108", () => {
    expect(hzToMidi(4186)).toBeCloseTo(108, 1);
  });

  it("무효 입력(0/음수)은 NaN — indicator dot 숨김 신호", () => {
    expect(Number.isNaN(hzToMidi(0))).toBe(true);
    expect(Number.isNaN(hzToMidi(-100))).toBe(true);
  });
});

describe("midiToRingCoord 결정성 (G2)", () => {
  it("구간 중앙(MIDI 60, [48,72]) = 12시 위쪽 { cx: 0, cy: -120 }", () => {
    const coord = midiToRingCoord(60, 48, 72, RING_RADIUS);
    expect(coord.cx).toBeCloseTo(0, 6);
    expect(coord.cy).toBeCloseTo(-120, 6);
  });

  it("구간 최저(MIDI 48) = arc 시작 -135° (좌하단)", () => {
    const coord = midiToRingCoord(48, 48, 72, RING_RADIUS);
    // -135°: cx = r*sin(-135°) ≈ -84.85, cy = -r*cos(-135°) ≈ 84.85
    expect(coord.cx).toBeCloseTo(-84.853, 2);
    expect(coord.cy).toBeCloseTo(84.853, 2);
  });

  it("구간 최고(MIDI 72) = arc 끝 135° (우하단)", () => {
    const coord = midiToRingCoord(72, 48, 72, RING_RADIUS);
    expect(coord.cx).toBeCloseTo(84.853, 2);
    expect(coord.cy).toBeCloseTo(84.853, 2);
  });

  it("구간 밖 값은 arc 끝으로 clamp (dot 이 arc 밖으로 안 나감)", () => {
    const tooHigh = midiToRingCoord(200, 48, 72, RING_RADIUS);
    const atMax = midiToRingCoord(72, 48, 72, RING_RADIUS);
    expect(tooHigh.cx).toBeCloseTo(atMax.cx, 6);
    expect(tooHigh.cy).toBeCloseTo(atMax.cy, 6);
  });

  it("비정상 구간(high<=low)은 0 비율로 안전 처리", () => {
    expect(midiToRingPercent(60, 72, 48)).toBe(0);
  });
});

describe("progress ring dashoffset 결정성 (G3)", () => {
  const circumference = arcCircumference(RING_RADIUS);

  it("0% → dashoffset = circumference (보이지 않음)", () => {
    expect(progressDashOffset(circumference, 0)).toBeCloseTo(circumference, 6);
  });

  it("100% → dashoffset = 0 (꽉 참)", () => {
    expect(progressDashOffset(circumference, 100)).toBeCloseTo(0, 6);
  });

  it("50% → dashoffset = circumference / 2", () => {
    expect(progressDashOffset(circumference, 50)).toBeCloseTo(
      circumference / 2,
      6,
    );
  });

  it("범위 밖 입력(음수/100 초과)은 [0,100] 으로 clamp", () => {
    expect(progressDashOffset(circumference, -20)).toBeCloseTo(circumference, 6);
    expect(progressDashOffset(circumference, 250)).toBeCloseTo(0, 6);
  });
});

describe("ringPercentToCoord arc clamp", () => {
  it("percent 가 [0,1] 밖이면 arc 양 끝으로 clamp", () => {
    const below = ringPercentToCoord(-0.5, RING_RADIUS);
    const atZero = ringPercentToCoord(0, RING_RADIUS);
    expect(below.cx).toBeCloseTo(atZero.cx, 6);
    expect(below.cy).toBeCloseTo(atZero.cy, 6);
  });
});
