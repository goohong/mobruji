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
 * 옥타브 선택 입력용 후보 노트(C2 ~ C6 한 옥타브 단위).
 * voice-range-input.md Q1 결정(옥타브 분류) 1차 PoC 단순화.
 */
export function octaveAnchorMidis(): number[] {
  const result: number[] = [];
  // C2 = 36, ..., C6 = 84
  for (let midi = 36; midi <= 84; midi += 1) {
    result.push(midi);
  }
  return result;
}
