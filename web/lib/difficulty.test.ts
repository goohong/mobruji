/**
 * deriveDifficulty 경계값 단위 테스트.
 *
 * 분류 기준:
 *   - HARD  : highMidi >= 76 또는 span >= 17
 *   - NORMAL: 71 <= highMidi <= 75
 *   - EASY  : highMidi < 71
 */

import { describe, expect, it } from "vitest";

import { deriveDifficulty, difficultyLabel } from "./difficulty";

describe("deriveDifficulty", () => {
  it("highMidi < 71 이고 음역폭이 좁으면 EASY", () => {
    // span=12(한 옥타브) F3(53)~F4(65): highMidi<71, span<17 → EASY
    expect(deriveDifficulty(53, 65)).toBe("EASY");
    // 경계 직전: highMidi=70 (A#4), span=16
    expect(deriveDifficulty(54, 70)).toBe("EASY");
  });

  it("highMidi가 71(B4) 이상 75(D#5) 이하면 NORMAL (단, span < 17)", () => {
    // 경계 하한 B4=71
    expect(deriveDifficulty(55, 71)).toBe("NORMAL");
    // 경계 상한 D#5=75
    expect(deriveDifficulty(60, 75)).toBe("NORMAL");
  });

  it("highMidi >= 76(E5) 이면 HARD", () => {
    // 경계 하한 E5=76
    expect(deriveDifficulty(60, 76)).toBe("HARD");
    // 높은 음
    expect(deriveDifficulty(64, 84)).toBe("HARD");
  });

  it("음역폭(span) >= 17 반음이면 highMidi가 NORMAL 범위여도 HARD", () => {
    // low=54(F#3), high=71(B4), span=17 → HARD
    expect(deriveDifficulty(54, 71)).toBe("HARD");
    // span=16 (경계 직전, NORMAL 유지)
    expect(deriveDifficulty(55, 71)).toBe("NORMAL");
  });

  it("음역폭(span) >= 17 이면 highMidi가 EASY 범위여도 HARD", () => {
    // low=53, high=70, span=17 → HARD (highMidi<71 이지만 음역폭 기준 충족)
    expect(deriveDifficulty(53, 70)).toBe("HARD");
  });
});

describe("difficultyLabel", () => {
  it("난이도 enum을 표시 라벨로 매핑", () => {
    expect(difficultyLabel("EASY")).toBe("Easy");
    expect(difficultyLabel("NORMAL")).toBe("Normal");
    expect(difficultyLabel("HARD")).toBe("Hard");
  });
});
