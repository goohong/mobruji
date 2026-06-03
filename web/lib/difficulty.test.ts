/**
 * deriveDifficulty 경계값 단위 테스트.
 *
 * 분류 기준:
 *   - HARD  : highMidi >= 76 또는 span >= 17
 *   - NORMAL: 71 <= highMidi <= 75
 *   - EASY  : highMidi < 71
 */

import { describe, expect, it } from "vitest";

import {
  deriveDifficulty,
  difficultyLabel,
  resolveSongDifficulty,
} from "./difficulty";

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

describe("deriveDifficulty 비정상 입력 (회귀 방지 고정)", () => {
  // 검증 책임은 호출 측에 있지만 헬퍼의 현재 동작을 명시적으로 고정한다.
  it("NaN 입력은 모든 비교가 false → EASY로 분류", () => {
    expect(deriveDifficulty(Number.NaN, 80)).toBe("HARD"); // highMidi=80 ≥ 76
    expect(deriveDifficulty(60, Number.NaN)).toBe("EASY"); // 모든 비교 false
    expect(deriveDifficulty(Number.NaN, Number.NaN)).toBe("EASY");
  });

  it("Infinity 입력은 highMidi 무한대 → HARD, low 무한대 → span 음의 무한대로 EASY", () => {
    expect(deriveDifficulty(60, Number.POSITIVE_INFINITY)).toBe("HARD");
    expect(deriveDifficulty(Number.NEGATIVE_INFINITY, 60)).toBe("HARD"); // span=+Infinity ≥ 17
    expect(deriveDifficulty(Number.POSITIVE_INFINITY, 60)).toBe("EASY"); // span=-Infinity
  });

  it("매우 작은/음수 highMidi 는 highMidi+span 기준 그대로 적용", () => {
    // low=-100, high=-50: highMidi<71 이지만 span=50 ≥ 17 → HARD
    expect(deriveDifficulty(-100, -50)).toBe("HARD");
    // low=-50, high=-40: highMidi<71, span=10<17 → EASY
    expect(deriveDifficulty(-50, -40)).toBe("EASY");
    expect(deriveDifficulty(0, 0)).toBe("EASY");
  });

  it("low > high 역전(span 음수)은 highMidi 기준만 적용", () => {
    expect(deriveDifficulty(80, 60)).toBe("EASY"); // highMidi=60 < 71
    expect(deriveDifficulty(90, 76)).toBe("HARD"); // highMidi=76 ≥ 76
  });

  it("low === high (span=0)은 highMidi 기준만 적용", () => {
    expect(deriveDifficulty(76, 76)).toBe("HARD");
    expect(deriveDifficulty(71, 71)).toBe("NORMAL");
    expect(deriveDifficulty(70, 70)).toBe("EASY");
  });
});

describe("difficultyLabel", () => {
  it("난이도 enum을 표시 라벨로 매핑", () => {
    expect(difficultyLabel("EASY")).toBe("Easy");
    expect(difficultyLabel("NORMAL")).toBe("Normal");
    expect(difficultyLabel("HARD")).toBe("Hard");
  });
});

describe("resolveSongDifficulty", () => {
  it("difficulty 가 있으면 그 값을 우선한다 (BE 우선)", () => {
    expect(
      resolveSongDifficulty({ difficulty: "HARD", lowMidi: 50, highMidi: 55 }),
    ).toBe("HARD");
  });

  it("difficulty 가 없고 low/high 가 있으면 client-side 계산값을 돌려준다", () => {
    // highMidi=77(F5) → HARD
    expect(resolveSongDifficulty({ lowMidi: 55, highMidi: 77 })).toBe("HARD");
  });

  it("난이도 정보가 전혀 없으면 null 을 돌려준다", () => {
    expect(resolveSongDifficulty({})).toBeNull();
    expect(
      resolveSongDifficulty({ difficulty: null, lowMidi: null, highMidi: null }),
    ).toBeNull();
  });
});
