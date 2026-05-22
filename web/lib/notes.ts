/**
 * MIDI note number ↔ scientific pitch notation 변환 헬퍼.
 *
 * - MIDI 60 = C4 (middle C). MIDI 12 = C0.
 * - voice-range-input.md §3 "MIDI note number 또는 과학적 음표 표기법(예: `C4`)" 요구사항.
 * - 음역대 입력 페이지에서 사용자에게 "낮은 음 / 높은 음"을 음표명으로 보여주기 위함.
 * - 한국어 음명 병기 (이슈 #318 A안) — 일반 사용자가 `D3/E2` 같은 SPN을
 *   직관적으로 이해 못한다는 검수 피드백 반영. SPN은 학습용/정확도 보조.
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

/**
 * SPN 12반음과 1:1 매칭되는 한국어 음명 (이슈 #318).
 *
 * 결정 사항:
 * - 샤프는 `♯`(U+266F) 사용. ASCII `#`보다 정확하고 i18n 친화적.
 * - 한국 음악 교육 표준(계명창법) 정렬: 도/도♯/레/레♯/미/파/파♯/솔/솔♯/라/라♯/시.
 * - "시♭" 같은 플랫 표기는 사용 안 함 — SPN ↔ 한국어 1:1 매핑 가독성 우선.
 */
const KOREAN_PITCH_CLASSES = [
  "도",
  "도♯",
  "레",
  "레♯",
  "미",
  "파",
  "파♯",
  "솔",
  "솔♯",
  "라",
  "라♯",
  "시",
] as const;

export const MIN_MIDI = 12; // C0
export const MAX_MIDI = 119; // B8

export function midiToNoteName(midi: number): string {
  const pitchClass = PITCH_CLASSES[((midi % 12) + 12) % 12];
  const octave = Math.floor(midi / 12) - 1;
  return `${pitchClass}${octave}`;
}

/**
 * MIDI 정수를 한국어 음명 + 옥타브 숫자로 변환 (이슈 #318).
 *
 * 예: 60 → `"도4"`, 61 → `"도♯4"`, 69 → `"라4"`.
 * 옥타브 숫자는 SPN과 동일 규칙(C0=옥타브 0, C4=middle C=옥타브 4).
 */
export function midiToKoreanNoteName(midi: number): string {
  const pitchClass = KOREAN_PITCH_CLASSES[((midi % 12) + 12) % 12];
  const octave = Math.floor(midi / 12) - 1;
  return `${pitchClass}${octave}`;
}

/**
 * MIDI 정수를 "한국어 (SPN)" 병기 형식으로 변환 (이슈 #318 A안).
 *
 * 예: 60 → `"도4 (C4)"`, 69 → `"라4 (A4)"`.
 *
 * 차트 Y축처럼 공간 좁은 곳은 `midiToKoreanNoteName` 또는 `midiToNoteName`
 * 한쪽만 쓰고, 본문/카드/슬라이더처럼 공간 여유가 있는 곳에서 병기 사용.
 */
export function midiToCombinedNoteName(midi: number): string {
  return `${midiToKoreanNoteName(midi)} (${midiToNoteName(midi)})`;
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
