/**
 * sampler.ts 단위 테스트.
 *
 * voice-range-auto-measurement.md §6 PR C / §7 단위(안정성 필터) / Q3.
 * - vi.useFakeTimers로 setInterval을 deterministic하게 advance.
 * - readFrame을 mock으로 주입해 Web Audio API 의존을 제거.
 */

import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from "vitest";

import {
  MEASUREMENT_DURATION_MS,
  SAMPLE_INTERVAL_MS,
  STABLE_FRAMES_REQUIRED,
  runMeasurementSession,
} from "./sampler";
import { frequencyToMidi } from "./midiConvert";

const A4_HZ = 440;
const C4_HZ = 261.6255653005986;

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("runMeasurementSession", () => {
  it("연속 STABLE_FRAMES_REQUIRED 프레임 동일 MIDI(±1) 안정 픽이면 confirmed=true로 조기 종료", async () => {
    const readFrame = vi.fn().mockImplementation(() => ({
      frequencyHz: A4_HZ,
      clarity: 0.95,
      isStable: true,
    }));

    const promise = runMeasurementSession({
      phase: "high",
      readFrame,
    });

    // 5프레임 advance → 안정 픽 confirm.
    await vi.advanceTimersByTimeAsync(SAMPLE_INTERVAL_MS * STABLE_FRAMES_REQUIRED);
    const result = await promise;

    expect(result.confirmed).toBe(true);
    expect(result.midi).toBe(Math.round(frequencyToMidi(A4_HZ))); // 69
    expect(result.stableSampleCount).toBe(STABLE_FRAMES_REQUIRED);
  });

  it("clarity 낮은 샘플은 안정 픽으로 카운트되지 않고 duration 만료 시 confirmed=false", async () => {
    const readFrame = vi.fn().mockImplementation(() => ({
      frequencyHz: A4_HZ,
      clarity: 0.5,
      isStable: false,
    }));

    const promise = runMeasurementSession({
      phase: "high",
      readFrame,
    });

    await vi.advanceTimersByTimeAsync(MEASUREMENT_DURATION_MS + SAMPLE_INTERVAL_MS);
    const result = await promise;

    expect(result.confirmed).toBe(false);
    expect(result.stableSampleCount).toBe(0);
    expect(result.midi).toBeNull();
  });

  it("연속성이 깨지면 윈도우가 리셋되어 confirm 되지 않는다", async () => {
    // pattern: A4(stable), A4, C4(아주 다름, stable), C4, C4, C4, C4 → C4가 5연속이 안 되므로 reject
    // 더 명확히: A4, A4, A4, A4(4회) → 깬다 → C4(5회) → C4가 5연속이면 confirm.
    const sequence = [
      { frequencyHz: A4_HZ, clarity: 0.95, isStable: true },
      { frequencyHz: A4_HZ, clarity: 0.95, isStable: true },
      { frequencyHz: A4_HZ, clarity: 0.95, isStable: true },
      { frequencyHz: A4_HZ, clarity: 0.95, isStable: true },
      // 윈도우 깨짐 (MIDI 차이 9 > 1)
      { frequencyHz: C4_HZ, clarity: 0.95, isStable: true },
      { frequencyHz: C4_HZ, clarity: 0.95, isStable: true },
      { frequencyHz: C4_HZ, clarity: 0.95, isStable: true },
      { frequencyHz: C4_HZ, clarity: 0.95, isStable: true },
      // 5번째 C4
      { frequencyHz: C4_HZ, clarity: 0.95, isStable: true },
    ];
    let idx = 0;
    const readFrame = vi.fn().mockImplementation(() => sequence[idx++] ?? sequence[sequence.length - 1]);

    const promise = runMeasurementSession({
      phase: "low",
      readFrame,
    });

    await vi.advanceTimersByTimeAsync(SAMPLE_INTERVAL_MS * sequence.length);
    const result = await promise;

    expect(result.confirmed).toBe(true);
    // 마지막 C4 5연속에서 confirm → MIDI 60.
    expect(result.midi).toBe(60);
  });

  it("onSample 콜백이 매 프레임 호출된다", async () => {
    const onSample = vi.fn();
    const readFrame = vi.fn().mockReturnValue({
      frequencyHz: A4_HZ,
      clarity: 0.95,
      isStable: true,
    });

    const promise = runMeasurementSession({
      phase: "high",
      readFrame,
      onSample,
    });

    await vi.advanceTimersByTimeAsync(SAMPLE_INTERVAL_MS * STABLE_FRAMES_REQUIRED);
    await promise;

    expect(onSample).toHaveBeenCalledTimes(STABLE_FRAMES_REQUIRED);
    expect(onSample.mock.calls[0][0].midi).toBe(69);
  });

  it("AbortSignal 으로 외부에서 즉시 종료할 수 있다", async () => {
    const controller = new AbortController();
    const readFrame = vi.fn().mockReturnValue({
      frequencyHz: 0,
      clarity: 0,
      isStable: false,
    });

    const promise = runMeasurementSession({
      phase: "low",
      readFrame,
      signal: controller.signal,
    });

    controller.abort();
    const result = await promise;
    expect(result.confirmed).toBe(false);
    expect(result.totalSampleCount).toBe(0);
  });
});
