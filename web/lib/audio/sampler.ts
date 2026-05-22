/**
 * 마이크 입력 → pitch 샘플 → phase 방향 극값 MIDI 추출.
 *
 * voice-range-auto-measurement.md §5-4 / §6 PR C / §8 Q2(5초 고정 — 조기 종료 폐기),
 * Q3(안정성 임계), Q5(iOS Safari user-gesture로 AudioContext).
 *
 * 책임 분리:
 *   - {@link runMeasurementSession} — 5초 동안 100ms 간격으로 detectPitch를 호출,
 *     안정 샘플을 누적한 뒤 phase 방향 percentile(low→P5, high→P95)로 결정.
 *   - 외부 의존(`detectPitch` / 시간 / Analyser 데이터)을 모두 옵션으로 주입할 수 있게
 *     해서 jest/vitest happy-dom 환경에서도 Web Audio mock 없이 검증 가능하도록 한다.
 *
 * 보안/privacy: audio 샘플은 본 모듈 안에서만 다루고 외부로 노출하지 않는다.
 * 결과(`MeasurementResult`)는 MIDI 정수와 메타데이터(샘플 개수/안정성)만 포함한다.
 *
 * ## 5초 동기화 (issue #313)
 * 이전 구현(2026-05-21)은 §8 Q2 권고 (c) "안정 픽 감지 시 조기 종료"를 따랐고,
 * 연속 5프레임(=500ms)만에 종료되어 진행 바 안내(5초)와 실측 시간이 어긋났다.
 * 사용자 검수 결과 Q2 결정을 (a) "5초 고정"으로 변경 — 다양한 음역을 시도할
 * 시간을 보장하고 percentile 기반으로 첫 발성/끝맺음 outlier에도 robust하게 한다.
 * confirmed 의미는 "안정 샘플이 MIN_STABLE_SAMPLES 이상 모임"으로 재정의.
 */

import { detectPitch as defaultDetectPitch } from "./pitchDetector";
import { frequencyToMidi } from "./midiConvert";

/**
 * §8 Q2 결정(2026-05-22 갱신): phase당 측정 시간 5초 고정.
 * 5초 동안 안정 샘플을 누적 → phase 방향 percentile로 결정 (조기 종료 폐기).
 */
export const MEASUREMENT_DURATION_MS = 5000;
/** 샘플 주기. 100ms = 10fps. AnalyserNode FFT cost와 충돌 없도록 보수적으로 둔다. */
export const SAMPLE_INTERVAL_MS = 100;
/**
 * confirmed=true 판정 최소 안정 샘플 수.
 * 5초 / 100ms = 50 프레임 중 약 20% (=10) 가 안정이면 신뢰 가능.
 */
export const MIN_STABLE_SAMPLES = 10;
/**
 * low phase percentile (안정 샘플 MIDI 오름차순 P5).
 * 첫 발성 흔들림 outlier 1~2개에 휘둘리지 않도록 min 대신 P5 사용.
 */
export const LOW_PERCENTILE = 0.05;
/** high phase percentile (안정 샘플 MIDI 오름차순 P95). */
export const HIGH_PERCENTILE = 0.95;

export type MeasurementPhase = "low" | "high";

export interface PitchSample {
  /** 측정 시작 후 경과 시간(ms). */
  readonly elapsedMs: number;
  /** 추정 Hz. 무음/노이즈는 0. */
  readonly frequencyHz: number;
  /** 0~1 clarity. */
  readonly clarity: number;
  /** clarity >= 임계 시 true. */
  readonly isStable: boolean;
  /** 반올림된 정수 MIDI. 무효 입력이면 null. */
  readonly midi: number | null;
}

export interface MeasurementResult {
  /** phase 방향 percentile MIDI 정수. 안정 샘플이 0이면 null. */
  readonly midi: number | null;
  /** 안정 샘플이 `MIN_STABLE_SAMPLES` 이상 모였는지 (UI 신뢰도 배지에 사용). */
  readonly confirmed: boolean;
  /** 수집된 안정 샘플 수. */
  readonly stableSampleCount: number;
  /** 전체 샘플 수. */
  readonly totalSampleCount: number;
}

/**
 * 측정 세션 옵션. 운영 코드는 기본값(getUserMedia 스트림 + setInterval)을,
 * 테스트는 의존성을 직접 주입해 deterministic하게 검증한다.
 */
export interface MeasurementOptions {
  readonly phase: MeasurementPhase;
  /** 매 샘플마다 호출되어 현재 프레임 정보를 UI에 전달. */
  readonly onSample?: (sample: PitchSample) => void;
  /** 측정 시간(ms). 기본 5000. */
  readonly durationMs?: number;
  /** 샘플 주기(ms). 기본 100. */
  readonly sampleIntervalMs?: number;
  /**
   * 한 프레임의 pitch를 계산. 기본 구현은 `AnalyserNode.getFloatTimeDomainData` →
   * `detectPitch`. 테스트는 mock 함수를 주입한다.
   */
  readonly readFrame: (elapsedMs: number) => {
    frequencyHz: number;
    clarity: number;
    isStable: boolean;
  };
  /** AbortSignal로 외부에서 중단 가능. */
  readonly signal?: AbortSignal;
}

/**
 * 한 phase의 측정을 실행한다.
 *
 * - 매 `sampleIntervalMs` 마다 `readFrame`을 호출해 pitch sample을 모은다.
 * - **`durationMs` 전체를 채운다** (issue #313): 조기 종료 없이 사용자가 다양한 음을
 *   시도할 시간을 보장하고 첫/끝 흔들림 outlier를 percentile로 흡수.
 * - 만료 시 안정 샘플(`isStable && midi !== null`)을 오름차순 정렬한 뒤 phase 방향
 *   percentile (low → `LOW_PERCENTILE` = P5, high → `HIGH_PERCENTILE` = P95)을 반환.
 * - 안정 샘플이 `MIN_STABLE_SAMPLES` 이상이면 `confirmed=true`.
 * - 안정 샘플이 0이면 `midi=null` + `confirmed=false`.
 * - `signal.abort()` 시 즉시 종료 — 누적된 샘플로 동일 percentile 계산.
 */
export async function runMeasurementSession(
  options: MeasurementOptions,
): Promise<MeasurementResult> {
  const durationMs = options.durationMs ?? MEASUREMENT_DURATION_MS;
  const intervalMs = options.sampleIntervalMs ?? SAMPLE_INTERVAL_MS;
  const samples: PitchSample[] = [];

  return new Promise((resolve) => {
    let cancelled = false;
    let elapsed = 0;

    const finalize = (): void => {
      if (cancelled) {
        return;
      }
      cancelled = true;
      clearInterval(timer);
      const stableMidis = samples
        .filter((sample) => sample.isStable && sample.midi !== null)
        .map((sample) => sample.midi as number)
        .filter((midi) => Number.isFinite(midi));
      const stableSampleCount = stableMidis.length;
      const midi =
        stableSampleCount === 0
          ? null
          : pickPhasePercentile(stableMidis, options.phase);
      resolve({
        midi,
        confirmed: stableSampleCount >= MIN_STABLE_SAMPLES,
        stableSampleCount,
        totalSampleCount: samples.length,
      });
    };

    const onTick = (): void => {
      if (cancelled) {
        return;
      }
      elapsed += intervalMs;
      const frame = options.readFrame(elapsed);
      const continuousMidi = frequencyToMidi(frame.frequencyHz);
      const midi = Number.isFinite(continuousMidi)
        ? Math.round(continuousMidi)
        : null;
      const sample: PitchSample = {
        elapsedMs: elapsed,
        frequencyHz: frame.frequencyHz,
        clarity: frame.clarity,
        isStable: frame.isStable,
        midi,
      };
      samples.push(sample);
      options.onSample?.(sample);

      if (elapsed >= durationMs) {
        finalize();
      }
    };

    const timer = setInterval(onTick, intervalMs);
    options.signal?.addEventListener("abort", finalize, { once: true });
  });
}

/**
 * 안정 샘플 MIDI 배열에서 phase 방향 percentile 값을 정수로 반환.
 *
 * - low phase → `LOW_PERCENTILE`(P5) floor: 가장 낮은 안정 음 중 outlier 1~2개 제외.
 * - high phase → `HIGH_PERCENTILE`(P95) ceil: 가장 높은 안정 음 중 outlier 1~2개 제외.
 *
 * 샘플이 1개뿐이면 그 값을 그대로 반환.
 */
function pickPhasePercentile(
  midis: readonly number[],
  phase: MeasurementPhase,
): number {
  const sorted = [...midis].sort((a, b) => a - b);
  if (sorted.length === 1) {
    return sorted[0];
  }
  const percentile = phase === "low" ? LOW_PERCENTILE : HIGH_PERCENTILE;
  const rawIndex = percentile * (sorted.length - 1);
  const index =
    phase === "low" ? Math.floor(rawIndex) : Math.ceil(rawIndex);
  const clampedIndex = Math.max(0, Math.min(sorted.length - 1, index));
  return Math.round(sorted[clampedIndex]);
}

/**
 * AudioContext + AnalyserNode + MediaStreamSource wiring.
 *
 * iOS Safari는 user-gesture 안에서 `new AudioContext()`를 호출해야 한다
 * (§8 Q5 결정 (a)). 따라서 페이지에서 사용자 버튼 클릭 핸들러에서 호출한다.
 *
 * 반환된 객체로 `readFrame`을 만들어 `runMeasurementSession`에 주입한다.
 */
export interface MicAnalyser {
  readonly sampleRate: number;
  readonly bufferLength: number;
  /** 호출자가 미리 할당한 버퍼에 시간 도메인 샘플을 채워준다. Pitchy/Web Audio 시그니처에 맞춰 ArrayBuffer-backed. */
  readFloatTimeDomainData: (target: Float32Array<ArrayBuffer>) => void;
  close: () => Promise<void>;
}

export async function openMicAnalyser(stream: MediaStream): Promise<MicAnalyser> {
  // Safari prefix 대비.
  const Ctor: typeof AudioContext =
    (window as unknown as { AudioContext?: typeof AudioContext }).AudioContext ??
    (window as unknown as { webkitAudioContext?: typeof AudioContext })
      .webkitAudioContext!;
  const context = new Ctor();
  // iOS Safari: context가 suspended 상태로 시작할 수 있으므로 resume.
  if (context.state === "suspended") {
    await context.resume();
  }
  const source = context.createMediaStreamSource(stream);
  const analyser = context.createAnalyser();
  analyser.fftSize = 2048;
  source.connect(analyser);
  return {
    sampleRate: context.sampleRate,
    bufferLength: analyser.fftSize,
    readFloatTimeDomainData: (target) => {
      analyser.getFloatTimeDomainData(target);
    },
    close: async () => {
      try {
        source.disconnect();
      } catch {
        // disconnect 중복 호출 무시.
      }
      await context.close();
    },
  };
}

/**
 * 운영 코드에서 한 줄로 쓰는 어댑터: MicAnalyser → readFrame.
 */
export function createReadFrameFromAnalyser(
  analyser: MicAnalyser,
  detectPitchImpl: typeof defaultDetectPitch = defaultDetectPitch,
): (elapsedMs: number) => {
  frequencyHz: number;
  clarity: number;
  isStable: boolean;
} {
  // 명시적으로 ArrayBuffer-backed Float32Array를 만들어 detectPitch의 시그니처
  // (Pitchy `findPitch`는 `Float32Array<ArrayBuffer>` generic을 요구)와 호환되도록 한다.
  const buffer: Float32Array<ArrayBuffer> = new Float32Array(
    new ArrayBuffer(analyser.bufferLength * 4),
  );
  return () => {
    analyser.readFloatTimeDomainData(buffer);
    const result = detectPitchImpl(buffer, analyser.sampleRate);
    return {
      frequencyHz: result.frequencyHz,
      clarity: result.clarity,
      isStable: result.isStable,
    };
  };
}
