/**
 * Pitch detection helper — Pitchy(McLeod Pitch Method) wrap.
 *
 * voice-range-auto-measurement.md §5-3 / §6 PR B.
 * - 단일 오디오 프레임(Float32Array)을 받아 `{ frequencyHz, clarity, isStable }`을 반환.
 * - clarity < UNSTABLE_CLARITY_THRESHOLD(0.9) 면 `isStable: false`로 표시,
 *   호출자(측정 세션)에서 해당 샘플을 reject 할 수 있도록 한다.
 * - Pitchy `PitchDetector`는 동일 입력 길이에서 재사용 가능 → 모듈 캐시로 보관해
 *   매 프레임 인스턴스 생성 비용을 회피한다.
 *
 * 실시간 마이크 스트림 wiring(AudioContext / AnalyserNode / requestAnimationFrame)은
 * PR C 측정 페이지에서 본 헬퍼를 호출하는 형태로 분리한다.
 */

import { PitchDetector as PitchyDetector } from "pitchy";

/** clarity가 이 값 미만이면 `isStable=false`로 표시한다. spec §3 신뢰도 표시. */
export const UNSTABLE_CLARITY_THRESHOLD = 0.9;

export interface PitchDetectionResult {
  /** 추정 주파수(Hz). 신호가 없거나 신뢰도가 0이면 0. */
  readonly frequencyHz: number;
  /** Pitchy clarity 값(0~1). 1에 가까울수록 명확한 단일 pitch. */
  readonly clarity: number;
  /** clarity >= UNSTABLE_CLARITY_THRESHOLD 이면 true. */
  readonly isStable: boolean;
}

const detectorCache = new Map<number, PitchyDetector<Float32Array>>();

function getDetector(inputLength: number): PitchyDetector<Float32Array> {
  const cached = detectorCache.get(inputLength);
  if (cached !== undefined) {
    return cached;
  }
  const detector = PitchyDetector.forFloat32Array(inputLength);
  detectorCache.set(inputLength, detector);
  return detector;
}

/**
 * 단일 오디오 프레임에서 pitch를 추정한다.
 *
 * @param audioBuffer 시간 도메인 샘플(Float32Array). 길이는 보통 AnalyserNode.fftSize.
 * @param sampleRate AudioContext.sampleRate (예: 44100, 48000).
 * @returns `{ frequencyHz, clarity, isStable }`. 신뢰도가 0이면 `frequencyHz` 0.
 */
export function detectPitch(
  audioBuffer: Float32Array,
  sampleRate: number,
): PitchDetectionResult {
  if (audioBuffer.length === 0) {
    return { frequencyHz: 0, clarity: 0, isStable: false };
  }
  if (!Number.isFinite(sampleRate) || sampleRate <= 0) {
    return { frequencyHz: 0, clarity: 0, isStable: false };
  }
  const detector = getDetector(audioBuffer.length);
  const [frequencyHz, clarity] = detector.findPitch(audioBuffer, sampleRate);
  return {
    frequencyHz,
    clarity,
    isStable: clarity >= UNSTABLE_CLARITY_THRESHOLD,
  };
}

/** 테스트 격리를 위한 캐시 리셋. 운영 코드에서 호출할 일은 없다. */
export function __resetPitchDetectorCacheForTest(): void {
  detectorCache.clear();
}
