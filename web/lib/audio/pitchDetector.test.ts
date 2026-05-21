import { beforeEach, describe, expect, it } from "vitest";

import {
  __resetPitchDetectorCacheForTest,
  detectPitch,
  UNSTABLE_CLARITY_THRESHOLD,
} from "./pitchDetector";

const SAMPLE_RATE = 44100;
const FRAME_LENGTH = 2048;

function sineWave(frequencyHz: number, length: number, sampleRate: number): Float32Array {
  const buffer = new Float32Array(length);
  const twoPi = 2 * Math.PI;
  for (let i = 0; i < length; i += 1) {
    buffer[i] = Math.sin((twoPi * frequencyHz * i) / sampleRate);
  }
  return buffer;
}

function whiteNoise(length: number, seed = 1): Float32Array {
  // 결정적(deterministic) LCG — 테스트 재현성을 위해 Math.random 미사용.
  const buffer = new Float32Array(length);
  let state = seed;
  for (let i = 0; i < length; i += 1) {
    state = (state * 1664525 + 1013904223) >>> 0;
    buffer[i] = (state / 0xffffffff) * 2 - 1;
  }
  return buffer;
}

beforeEach(() => {
  __resetPitchDetectorCacheForTest();
});

describe("detectPitch", () => {
  it("440Hz 순수 사인파에서 ≈440Hz를 추정하고 clarity가 임계 이상이다", () => {
    const buffer = sineWave(440, FRAME_LENGTH, SAMPLE_RATE);

    const result = detectPitch(buffer, SAMPLE_RATE);

    expect(result.frequencyHz).toBeGreaterThan(435);
    expect(result.frequencyHz).toBeLessThan(445);
    expect(result.clarity).toBeGreaterThanOrEqual(UNSTABLE_CLARITY_THRESHOLD);
    expect(result.isStable).toBe(true);
  });

  it("880Hz 사인파(A5)에서도 안정 추정한다", () => {
    const buffer = sineWave(880, FRAME_LENGTH, SAMPLE_RATE);

    const result = detectPitch(buffer, SAMPLE_RATE);

    expect(result.frequencyHz).toBeGreaterThan(870);
    expect(result.frequencyHz).toBeLessThan(890);
    expect(result.isStable).toBe(true);
  });

  it("화이트 노이즈는 clarity가 낮아 isStable=false로 표시된다", () => {
    const buffer = whiteNoise(FRAME_LENGTH);

    const result = detectPitch(buffer, SAMPLE_RATE);

    expect(result.clarity).toBeLessThan(UNSTABLE_CLARITY_THRESHOLD);
    expect(result.isStable).toBe(false);
  });

  it("빈 버퍼는 0Hz / clarity 0 / isStable=false를 반환한다", () => {
    const result = detectPitch(new Float32Array(0), SAMPLE_RATE);

    expect(result).toEqual({ frequencyHz: 0, clarity: 0, isStable: false });
  });

  it("잘못된 sampleRate(0/음수/NaN)는 안전하게 0/0/false를 반환한다", () => {
    const buffer = sineWave(440, FRAME_LENGTH, SAMPLE_RATE);

    expect(detectPitch(buffer, 0)).toEqual({ frequencyHz: 0, clarity: 0, isStable: false });
    expect(detectPitch(buffer, -44100)).toEqual({
      frequencyHz: 0,
      clarity: 0,
      isStable: false,
    });
    expect(detectPitch(buffer, Number.NaN)).toEqual({
      frequencyHz: 0,
      clarity: 0,
      isStable: false,
    });
  });

  it("동일 입력 길이로 반복 호출해도 detector 캐시로 일관 결과를 낸다", () => {
    const buffer = sineWave(440, FRAME_LENGTH, SAMPLE_RATE);

    const first = detectPitch(buffer, SAMPLE_RATE);
    const second = detectPitch(buffer, SAMPLE_RATE);

    expect(second.frequencyHz).toBeCloseTo(first.frequencyHz, 6);
    expect(second.clarity).toBeCloseTo(first.clarity, 6);
  });
});
