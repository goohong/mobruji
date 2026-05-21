/**
 * 마이크 입력 → pitch 샘플 → 안정 픽 MIDI 추출.
 *
 * voice-range-auto-measurement.md §5-4 / §6 PR C / §8 Q2(5초 고정), Q3(안정성 임계),
 * Q5(iOS Safari user-gesture로 AudioContext).
 *
 * 책임 분리:
 *   - {@link createMeasurementSession} — 5초 동안 100ms 간격으로 detectPitch를 호출,
 *     안정 픽(연속 STABLE_FRAMES_REQUIRED 프레임, MIDI ±MAX_MIDI_TOLERANCE) 추출.
 *   - 외부 의존(`detectPitch` / 시간 / Analyser 데이터)을 모두 옵션으로 주입할 수 있게
 *     해서 jest/vitest happy-dom 환경에서도 Web Audio mock 없이 검증 가능하도록 한다.
 *
 * 보안/privacy: audio 샘플은 본 모듈 안에서만 다루고 외부로 노출하지 않는다.
 * 결과(`MeasurementResult`)는 MIDI 정수와 메타데이터(샘플 개수/안정성)만 포함한다.
 */

import { detectPitch as defaultDetectPitch } from "./pitchDetector";
import { frequencyToMidi } from "./midiConvert";

/** §8 Q2 결정: phase당 측정 시간 5초 고정. 안정 픽 감지 시 조기 종료. */
export const MEASUREMENT_DURATION_MS = 5000;
/** 샘플 주기. 100ms = 10fps. AnalyserNode FFT cost와 충돌 없도록 보수적으로 둔다. */
export const SAMPLE_INTERVAL_MS = 100;
/** 안정 픽 confirm 임계: 연속 동일 MIDI(±1) 5프레임. */
export const STABLE_FRAMES_REQUIRED = 5;
/** 연속 프레임 동일성 허용 오차(semitone). MIDI ±1까지 같은 음으로 간주. */
export const MAX_MIDI_TOLERANCE = 1;

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
  /** 안정 픽으로 confirm된 MIDI 정수. 안정 픽이 없으면 안정 샘플 중 phase에 맞는 극값(low→min, high→max), 그것도 없으면 null. */
  readonly midi: number | null;
  /** 안정 픽으로 confirm 되었는지 여부 (UI 신뢰도 배지에 사용). */
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
 * - 안정 픽(연속 STABLE_FRAMES_REQUIRED 프레임, MIDI ±MAX_MIDI_TOLERANCE)이 confirm 되면
 *   기다리지 않고 즉시 종료(§8 Q2 권고: 조기 종료).
 * - duration 내 confirm 실패 시 안정 샘플 중 phase 방향 극값(low→min, high→max)을 반환,
 *   confirmed=false. 그것도 없으면 midi=null.
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
    let confirmedMidi: number | null = null;
    const recentStableMidis: number[] = [];

    const finalize = (): void => {
      if (cancelled) {
        return;
      }
      cancelled = true;
      clearInterval(timer);
      const stableSamples = samples.filter(
        (sample) => sample.isStable && sample.midi !== null,
      );
      if (confirmedMidi !== null) {
        resolve({
          midi: confirmedMidi,
          confirmed: true,
          stableSampleCount: stableSamples.length,
          totalSampleCount: samples.length,
        });
        return;
      }
      // 안정 픽 confirm 실패 — fallback으로 phase 방향 극값을 반환한다.
      const stableMidis = stableSamples
        .map((sample) => sample.midi as number)
        .filter((midi) => Number.isFinite(midi));
      const fallbackMidi =
        stableMidis.length === 0
          ? null
          : options.phase === "low"
            ? Math.min(...stableMidis)
            : Math.max(...stableMidis);
      resolve({
        midi: fallbackMidi,
        confirmed: false,
        stableSampleCount: stableSamples.length,
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

      if (sample.isStable && sample.midi !== null) {
        // 연속 프레임 ±MAX_MIDI_TOLERANCE 윈도우를 유지한다.
        const last = recentStableMidis[recentStableMidis.length - 1];
        if (
          last === undefined ||
          Math.abs(sample.midi - last) <= MAX_MIDI_TOLERANCE
        ) {
          recentStableMidis.push(sample.midi);
        } else {
          recentStableMidis.length = 0;
          recentStableMidis.push(sample.midi);
        }
        if (recentStableMidis.length >= STABLE_FRAMES_REQUIRED) {
          // 윈도우 평균(반올림)으로 confirm — 단일 outlier에 휘둘리지 않게.
          const sum = recentStableMidis.reduce((acc, v) => acc + v, 0);
          confirmedMidi = Math.round(sum / recentStableMidis.length);
          finalize();
          return;
        }
      } else {
        recentStableMidis.length = 0;
      }

      if (elapsed >= durationMs) {
        finalize();
      }
    };

    const timer = setInterval(onTick, intervalMs);
    options.signal?.addEventListener("abort", finalize, { once: true });
  });
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
