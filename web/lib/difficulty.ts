/**
 * 가창 난이도 분류 헬퍼 (client-side 임시 계산).
 *
 * 배경:
 *   - 이슈 #75 사용자 결정(2026-05-21)으로 추천 카드에 "가창 난이도(EASY/NORMAL/HARD)"를
 *     노출하기로 함. backend(`Song`)에 `difficulty` 필드를 추가하는 작업은 별도 이슈 #77.
 *   - backend가 미래에 응답에 `difficulty` 필드를 추가하면 그 값을 우선 사용하고,
 *     없으면 본 헬퍼의 client-side fallback으로 계산한다 (SongCard 참고).
 *
 * 분류 기준 (이슈 #75/#77 본문과 동일):
 *   - HARD  : highMidi >= 76 (E5) 또는 (highMidi - lowMidi) >= 17 반음
 *   - NORMAL: 71 <= highMidi <= 75 (B4 ~ D#5)
 *   - EASY  : highMidi < 71
 *
 * 입력은 반음(MIDI note number) 단위 정수. 잘못된 입력(예: low > high)은 그대로 분류한다 —
 * 본 헬퍼는 검증 책임을 지지 않고, 검증은 호출 측(DTO/Domain)에서 수행한다.
 */

export type Difficulty = "EASY" | "NORMAL" | "HARD";

const HIGH_HARD_THRESHOLD = 76;
const HIGH_NORMAL_THRESHOLD = 71;
const SPAN_HARD_THRESHOLD = 17;

export function deriveDifficulty(
  lowMidi: number,
  highMidi: number,
): Difficulty {
  const span = highMidi - lowMidi;
  if (highMidi >= HIGH_HARD_THRESHOLD || span >= SPAN_HARD_THRESHOLD) {
    return "HARD";
  }
  if (highMidi >= HIGH_NORMAL_THRESHOLD) {
    return "NORMAL";
  }
  return "EASY";
}

/**
 * UI 표시용 한국어 라벨. 텍스트만 사용(아이콘/별점 미사용 — 이슈 #75 사용자 권고).
 */
export function difficultyLabel(difficulty: Difficulty): string {
  switch (difficulty) {
    case "EASY":
      return "Easy";
    case "NORMAL":
      return "Normal";
    case "HARD":
      return "Hard";
  }
}
