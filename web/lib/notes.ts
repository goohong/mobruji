/**
 * MIDI note number ↔ scientific pitch notation 변환 헬퍼.
 *
 * - MIDI 60 = C4 (middle C). MIDI 12 = C0.
 * - voice-range-input.md §3 "MIDI note number 또는 과학적 음표 표기법(예: `C4`)" 요구사항.
 * - 음역대 입력 페이지에서 사용자에게 "낮은 음 / 높은 음"을 음표명으로 보여주기 위함.
 */

const PITCH_CLASSES = [
  "C",
  "C#",
  "D",
  "D#",
  "E",
  "F",
  "F#",
  "G",
  "G#",
  "A",
  "A#",
  "B",
] as const;

export const MIN_MIDI = 12; // C0
export const MAX_MIDI = 119; // B8

export function midiToNoteName(midi: number): string {
  const pitchClass = PITCH_CLASSES[((midi % 12) + 12) % 12];
  const octave = Math.floor(midi / 12) - 1;
  return `${pitchClass}${octave}`;
}

/**
 * 옥타브 선택 입력용 후보 노트(C2 ~ C6 닫힌 구간, 반음(semitone) 단위).
 * voice-range-input.md Q1 결정(옥타브 분류) 1차 PoC 단순화.
 *
 * 함수명이 "anchor"였던 과거 버전은 의미상 옥타브 시작음(C2/C3/...)만 반환할 것처럼 들렸으나
 * 실제 동작은 C2~C6 범위 전 반음을 반환한다. 사용처(`/voice-range` select)는 반음 단위 선택을
 * 요구하므로 동작은 유지하고 이름을 동작에 맞게 정정했다. PR #57(closes #53, #56).
 */
export function octaveRangeMidis(): number[] {
  const result: number[] = [];
  // C2 = 36, ..., C6 = 84
  for (let midi = 36; midi <= 84; midi += 1) {
    result.push(midi);
  }
  return result;
}
