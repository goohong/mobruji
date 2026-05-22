/**
 * sampler.ts 단위 테스트.
 *
 * voice-range-auto-measurement.md §6 PR C / §7 단위(안정성 필터) / Q3 / issue #313.
 * - vi.useFakeTimers로 setInterval을 deterministic하게 advance.
 * - readFrame을 mock으로 주입해 Web Audio API 의존을 제거.
 * - 핵심 동작: 5초 전체 측정 후 phase 방향 percentile(low→P5 / high→P95) 추출.
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
  HIGH_PERCENTILE,
  LOW_PERCENTILE,
  MEASUREMENT_DURATION_MS,
  MIN_STABLE_SAMPLES,
  SAMPLE_INTERVAL_MS,
  runMeasurementSession,
} from "./sampler";
import { frequencyToMidi } from "./midiConvert";

const A4_HZ = 440;

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("runMeasurementSession", () => {
  it("durationMs 전체를 채운다 — 안정 샘플이 모여도 조기 종료하지 않는다 (#313)", async () => {
    // 매 프레임 안정한 A4. 이전 구현은 5프레임(500ms)에 종료했지만,
    // 5초 동기화 후엔 setInterval이 5000/100 = 50번 tick 되어야 한다.
    const readFrame = vi.fn().mockReturnValue({
      frequencyHz: A4_HZ,
      clarity: 0.95,
      isStable: true,
    });

    const promise = runMeasurementSession({
      phase: "high",
      readFrame,
    });

    // 5프레임 advance만으로는 결과가 나오지 않아야 한다.
    await vi.advanceTimersByTimeAsync(SAMPLE_INTERVAL_MS * 5);
    let settled = false;
    // 'race' 가 아닌 microtask 큐 flush로 settle 여부 확인.
    void promise.then(() => {
      settled = true;
    });
    await Promise.resolve();
    expect(settled).toBe(false);

    // 전체 duration 채우면 종료.
    await vi.advanceTimersByTimeAsync(MEASUREMENT_DURATION_MS);
    const result = await promise;

    const expectedFrames = MEASUREMENT_DURATION_MS / SAMPLE_INTERVAL_MS;
    expect(result.totalSampleCount).toBe(expectedFrames);
    expect(result.confirmed).toBe(true);
    expect(result.midi).toBe(Math.round(frequencyToMidi(A4_HZ))); // 69
  });

  it("high phase는 안정 샘플 P95를 반환한다 — 가장 높은 outlier 1~2개에 흔들리지 않는다", async () => {
    // 50 프레임 중: outlier 2개(MIDI 80) + A4(MIDI 69) 48개.
    // 정렬: [69 × 48, 80, 80] → P95 = ceil(0.95 * 49) = 47번째(0-indexed) = 69.
    // outlier 1~2개가 P95에 잡히지 않음을 검증.
    const buildSeq = (
      runs: ReadonlyArray<{ count: number; hz: number; isStable: boolean }>,
    ): Array<{ frequencyHz: number; clarity: number; isStable: boolean }> => {
      const out: Array<{
        frequencyHz: number;
        clarity: number;
        isStable: boolean;
      }> = [];
      for (const run of runs) {
        for (let i = 0; i < run.count; i += 1) {
          out.push({
            frequencyHz: run.hz,
            clarity: run.isStable ? 0.95 : 0.3,
            isStable: run.isStable,
          });
        }
      }
      return out;
    };
    const outlierHz = 440 * Math.pow(2, (80 - 69) / 12); // MIDI 80
    const sequence = buildSeq([
      { count: 48, hz: A4_HZ, isStable: true },
      { count: 2, hz: outlierHz, isStable: true },
    ]);

    let idx = 0;
    const readFrame = vi.fn().mockImplementation(
      () => sequence[idx++] ?? sequence[sequence.length - 1],
    );

    const promise = runMeasurementSession({
      phase: "high",
      readFrame,
    });

    await vi.advanceTimersByTimeAsync(MEASUREMENT_DURATION_MS);
    const result = await promise;

    expect(result.totalSampleCount).toBe(50);
    expect(result.stableSampleCount).toBe(50);
    // P95 = ceil(0.95 * 49) = 47 → 정렬 후 인덱스 47 = A4(69). outlier 2개 흡수.
    expect(result.midi).toBe(69);
    expect(result.confirmed).toBe(true);
  });

  it("low phase는 안정 샘플 P5를 반환한다 — 가장 낮은 outlier에 흔들리지 않는다", async () => {
    // 매우 낮은 outlier(MIDI 40 부근) 5개 + A4 안정 45개.
    // P5 = floor(0.05 * 49) = floor(2.45) = 2 → 정렬 후 3번째 = outlier 영역.
    // → outlier가 5개나 있으면 P5가 outlier로 끌려갈 수 있음 — 이 테스트는
    // outlier가 1~2개일 때 robust함을 검증.
    const buildSeq = (
      runs: ReadonlyArray<{ count: number; hz: number; isStable: boolean }>,
    ): Array<{ frequencyHz: number; clarity: number; isStable: boolean }> => {
      const out: Array<{
        frequencyHz: number;
        clarity: number;
        isStable: boolean;
      }> = [];
      for (const run of runs) {
        for (let i = 0; i < run.count; i += 1) {
          out.push({
            frequencyHz: run.hz,
            clarity: run.isStable ? 0.95 : 0.3,
            isStable: run.isStable,
          });
        }
      }
      return out;
    };
    const lowOutlierHz = 440 * Math.pow(2, (40 - 69) / 12); // MIDI 40
    const sequence = buildSeq([
      { count: 2, hz: lowOutlierHz, isStable: true },
      { count: 48, hz: A4_HZ, isStable: true },
    ]);

    let idx = 0;
    const readFrame = vi.fn().mockImplementation(
      () => sequence[idx++] ?? sequence[sequence.length - 1],
    );

    const promise = runMeasurementSession({
      phase: "low",
      readFrame,
    });

    await vi.advanceTimersByTimeAsync(MEASUREMENT_DURATION_MS);
    const result = await promise;

    // P5 = floor(0.05 * 49) = 2 → 정렬 시 [outlier, outlier, A4, ...] → index 2 = A4(69).
    expect(result.midi).toBe(69);
    expect(result.confirmed).toBe(true);
  });

  it("clarity 낮은 샘플은 안정 픽으로 카운트되지 않고 5초 만료 시 confirmed=false + midi=null", async () => {
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

  it("안정 샘플이 MIN_STABLE_SAMPLES 미만이면 confirmed=false (midi는 P5/P95로 결정)", async () => {
    // 안정 샘플 5개 + 불안정 45개. MIN_STABLE_SAMPLES(10) 미만이므로 confirmed=false.
    const sequence: Array<{
      frequencyHz: number;
      clarity: number;
      isStable: boolean;
    }> = [];
    for (let i = 0; i < 5; i += 1) {
      sequence.push({ frequencyHz: A4_HZ, clarity: 0.95, isStable: true });
    }
    for (let i = 0; i < 45; i += 1) {
      sequence.push({ frequencyHz: 0, clarity: 0.1, isStable: false });
    }
    let idx = 0;
    const readFrame = vi.fn().mockImplementation(
      () => sequence[idx++] ?? sequence[sequence.length - 1],
    );

    const promise = runMeasurementSession({
      phase: "high",
      readFrame,
    });
    await vi.advanceTimersByTimeAsync(MEASUREMENT_DURATION_MS);
    const result = await promise;

    expect(result.stableSampleCount).toBe(5);
    expect(result.confirmed).toBe(false);
    expect(result.midi).toBe(69); // 안정 샘플이 모두 A4 → percentile=A4
  });

  it("onSample 콜백이 매 프레임 호출되어 진행 표시에 사용된다", async () => {
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

    await vi.advanceTimersByTimeAsync(MEASUREMENT_DURATION_MS);
    await promise;

    const expectedFrames = MEASUREMENT_DURATION_MS / SAMPLE_INTERVAL_MS;
    expect(onSample).toHaveBeenCalledTimes(expectedFrames);
    expect(onSample.mock.calls[0][0].midi).toBe(69);
    // 마지막 콜백의 elapsedMs가 duration과 같아야 한다 (진행 바 100% 도달 검증).
    expect(onSample.mock.calls[expectedFrames - 1][0].elapsedMs).toBe(
      MEASUREMENT_DURATION_MS,
    );
  });

  it("AbortSignal 으로 외부에서 즉시 종료할 수 있다 (누적 샘플로 percentile 계산)", async () => {
    const controller = new AbortController();
    const readFrame = vi.fn().mockReturnValue({
      frequencyHz: A4_HZ,
      clarity: 0.95,
      isStable: true,
    });

    const promise = runMeasurementSession({
      phase: "low",
      readFrame,
      signal: controller.signal,
    });

    // 일부 샘플만 모인 시점에 abort.
    await vi.advanceTimersByTimeAsync(SAMPLE_INTERVAL_MS * 3);
    controller.abort();
    const result = await promise;

    expect(result.totalSampleCount).toBe(3);
    expect(result.stableSampleCount).toBe(3);
    expect(result.confirmed).toBe(false); // MIN_STABLE_SAMPLES 미달
    expect(result.midi).toBe(69); // 모두 A4 → percentile=69
  });

  it("constants: LOW_PERCENTILE=0.05, HIGH_PERCENTILE=0.95, MIN_STABLE_SAMPLES=10", () => {
    // §8 Q2 결정 파라미터를 정수형 매직 넘버로 흩지 않도록 export 했는지 표시 테스트.
    expect(LOW_PERCENTILE).toBe(0.05);
    expect(HIGH_PERCENTILE).toBe(0.95);
    expect(MIN_STABLE_SAMPLES).toBe(10);
  });
});
