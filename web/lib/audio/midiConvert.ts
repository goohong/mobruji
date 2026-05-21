/**
 * Hz ↔ MIDI 변환 헬퍼.
 *
 * voice-range-auto-measurement.md §3 / §7 단위 테스트 항목.
 * - 표준 조율: A4(440Hz) = MIDI 69, C4(261.63Hz) ≈ MIDI 60.
 * - 백엔드 `lowestNoteMidi/highestNoteMidi`는 정수 MIDI([12, 119])를 받으므로
 *   `frequencyToMidiInt`로 반올림 결과를 함께 노출한다.
 *
 * 음표명(MIDI → "C4") 변환은 기존 `web/lib/notes.ts`의 `midiToNoteName`을
 * 그대로 재노출하여 단일 진실 원천을 유지한다.
 */

import { midiToNoteName } from "../notes";

const A4_FREQUENCY_HZ = 440;
const A4_MIDI = 69;

/**
 * Hz를 (연속) MIDI 값으로 변환한다.
 *
 * 입력이 0 이하이거나 유한값이 아니면 `NaN`을 반환한다 — 호출자가 안정성
 * 필터에서 무효 샘플로 폐기할 수 있도록 한다.
 */
export function frequencyToMidi(frequencyHz: number): number {
  if (!Number.isFinite(frequencyHz) || frequencyHz <= 0) {
    return Number.NaN;
  }
  return 12 * Math.log2(frequencyHz / A4_FREQUENCY_HZ) + A4_MIDI;
}

/**
 * Hz를 정수 MIDI 값으로 반올림 변환한다. 무효 입력은 `null`로 처리해 안정성
 * 필터 단계에서 명시적으로 reject 할 수 있게 한다.
 */
export function frequencyToMidiInt(frequencyHz: number): number | null {
  const midi = frequencyToMidi(frequencyHz);
  if (!Number.isFinite(midi)) {
    return null;
  }
  return Math.round(midi);
}

export { midiToNoteName };
