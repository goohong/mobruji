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

/**
 * 비유한(NaN/Infinity) MIDI 입력에 대한 placeholder (이슈 #757).
 *
 * 가드 부재 시 `PITCH_CLASSES[NaN] = undefined`, `Math.floor(NaN) = NaN` 합쳐져
 * `"undefinedNaN"` 문자열이 UI 에 노출되는 회귀를 1차 방어한다. 호출자가 사전
 * 필터링하면 보통 도달하지 않으나, audio analyzer (pitchy) 가 silence/noise 시
 * NaN 을 흘릴 수 있어 라이브러리 측에서 안전 placeholder 를 보장한다.
 */
export const INVALID_MIDI_PLACEHOLDER = "--";

/**
 * 비유한 MIDI 입력 시 사용할 a11y 친화 fallback 기본값 (이슈 #766).
 *
 * 호출자가 별도 fallback 을 지정하지 않고 a11y 컨텍스트(aria-label/스크린리더
 * 노출 텍스트)에서 사용할 수 있는 한국어 안내 문구. 스크린리더가 "--" 를
 * "dash dash" 로 읽어 의미를 잃는 문제를 차단한다.
 */
export const INVALID_MIDI_A11Y_FALLBACK = "음정 정보 없음";

/**
 * note 변환 호출자가 비유한 입력에 대해 표시 텍스트를 직접 지정할 수 있는 옵션.
 *
 * - `a11yFallback`: aria-label/스크린리더 노출 텍스트에서 "--" 대신 사용할 문자열.
 *   생략 시 시각용 placeholder("--") 가 그대로 반환되어 기존 동작과 호환된다.
 *   a11y 컨텍스트에서 명시적으로 `INVALID_MIDI_A11Y_FALLBACK` 또는 자체 문구를
 *   전달하기를 권장한다 (이슈 #766).
 */
export type NoteNameOptions = {
  readonly a11yFallback?: string;
};

export function midiToNoteName(
  midi: number,
  options?: NoteNameOptions,
): string {
  if (!Number.isFinite(midi)) {
    return options?.a11yFallback ?? INVALID_MIDI_PLACEHOLDER;
  }
  const pitchClass = PITCH_CLASSES[((midi % 12) + 12) % 12];
  const octave = Math.floor(midi / 12) - 1;
  return `${pitchClass}${octave}`;
}

/**
 * MIDI 정수를 한국어 음명 + 옥타브 숫자로 변환 (이슈 #318).
 *
 * 예: 60 → `"도4"`, 61 → `"도♯4"`, 69 → `"라4"`.
 * 옥타브 숫자는 SPN과 동일 규칙(C0=옥타브 0, C4=middle C=옥타브 4).
 *
 * 비유한 입력은 `INVALID_MIDI_PLACEHOLDER` 를 반환한다 (이슈 #757).
 * `options.a11yFallback` 을 전달하면 aria-label 등 a11y 컨텍스트에서 의미 있는
 * 텍스트로 대체할 수 있다 (이슈 #766).
 */
export function midiToKoreanNoteName(
  midi: number,
  options?: NoteNameOptions,
): string {
  if (!Number.isFinite(midi)) {
    return options?.a11yFallback ?? INVALID_MIDI_PLACEHOLDER;
  }
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
 *
 * 비유한 입력에 `options.a11yFallback` 이 전달되면 병기 형식 대신 fallback 만
 * 단독 반환한다 — "음정 정보 없음 (음정 정보 없음)" 같은 중복 노출 방지.
 */
export function midiToCombinedNoteName(
  midi: number,
  options?: NoteNameOptions,
): string {
  if (!Number.isFinite(midi) && options?.a11yFallback !== undefined) {
    return options.a11yFallback;
  }
  return `${midiToKoreanNoteName(midi)} (${midiToNoteName(midi)})`;
}

/**
 * 옥타브 선택 입력용 후보 노트(G2 ~ C6 닫힌 구간, 반음(semitone) 단위).
 * voice-range-input.md Q1 결정(옥타브 분류) 1차 PoC 단순화.
 *
 * 함수명이 "anchor"였던 과거 버전은 의미상 옥타브 시작음(C2/C3/...)만 반환할 것처럼 들렸으나
 * 실제 동작은 G2~C6 범위 전 반음을 반환한다. 사용처(`/voice-range` select)는 반음 단위 선택을
 * 요구하므로 동작은 유지하고 이름을 동작에 맞게 정정했다. PR #57(closes #53, #56).
 *
 * 하한은 실제 곡 보컬 음역 분포(C3=48 ~ C6=84)에 맞춰 G2(43)로 둔다 (#1853) —
 * 곡이 0개인 C2~F#2(36~42) dead-zone 을 후보에서 제거. `VoiceRangeSlider` 트랙
 * 하한(DEFAULT_MIN_MIDI=43)과 동일 밴드를 공유한다.
 */
export function octaveRangeMidis(): number[] {
  const result: number[] = [];
  // G2 = 43, ..., C6 = 84
  for (let midi = 43; midi <= 84; midi += 1) {
    result.push(midi);
  }
  return result;
}
