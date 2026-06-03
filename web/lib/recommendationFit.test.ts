/**
 * recommendationFit 헬퍼 단위 테스트 (이슈 #1484).
 *
 * 검증 포인트:
 *   1) 0~1 점수가 0~100 정수 퍼센트로 변환된다(반올림).
 *   2) 임계값(0.7 / 0.4)에 따라 high/mid/low 레벨 + 대응 톤 클래스가 매핑된다.
 *   3) 범위를 벗어난 입력은 [0, 1] 로 clamp 된다.
 */

import { describe, expect, it } from "vitest";

import { toFitDisplay } from "./recommendationFit";

describe("toFitDisplay", () => {
  it("0~1 점수를 0~100 정수 퍼센트로 반올림한다", () => {
    expect(toFitDisplay(0.0).percent).toBe(0);
    expect(toFitDisplay(0.426).percent).toBe(43);
    expect(toFitDisplay(1).percent).toBe(100);
  });

  it("0.7 이상은 high + success 톤", () => {
    const display = toFitDisplay(0.7);
    expect(display.level).toBe("high");
    expect(display.toneClass).toContain("--badge-success");
  });

  it("0.4 이상 0.7 미만은 mid + warning 톤", () => {
    const display = toFitDisplay(0.4);
    expect(display.level).toBe("mid");
    expect(display.toneClass).toContain("--badge-warning");
  });

  it("0.4 미만은 low + neutral 톤", () => {
    const display = toFitDisplay(0.39);
    expect(display.level).toBe("low");
    expect(display.toneClass).toContain("--badge-neutral");
  });

  it("범위를 벗어난 입력은 [0, 1] 로 clamp 한다", () => {
    expect(toFitDisplay(1.5).percent).toBe(100);
    expect(toFitDisplay(-0.5).percent).toBe(0);
    expect(toFitDisplay(-0.5).level).toBe("low");
  });
});
