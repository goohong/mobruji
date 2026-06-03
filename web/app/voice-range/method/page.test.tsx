/**
 * VoiceRangeMethodPage 테스트 (directive #1511).
 *
 * 검증:
 *  - '자동(마이크) 측정' → /voice-range/auto, '직접 입력' → /voice-range 동등 분기.
 *  - 진입만으로 마이크 권한을 요청하지 않음을 안내 카피로 보장(자동 트리거 부재).
 *  - a11y 위반 0.
 */

import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

import VoiceRangeMethodPage from "./page";
import { expectNoA11yViolations } from "@/lib/test-helpers/a11y";

afterEach(() => cleanup());

describe("VoiceRangeMethodPage", () => {
  it("자동 측정은 /voice-range/auto, 직접 입력은 /voice-range 로 연결한다", () => {
    render(<VoiceRangeMethodPage />);
    expect(
      screen.getByRole("link", { name: /자동\(마이크\) 측정/ }),
    ).toHaveAttribute("href", "/voice-range/auto");
    expect(
      screen.getByRole("link", { name: /직접 입력/ }),
    ).toHaveAttribute("href", "/voice-range");
  });

  it("진입만으로 마이크 권한을 요청하지 않음을 안내한다", () => {
    render(<VoiceRangeMethodPage />);
    expect(
      screen.getByText(/권한을 요청하지 않습니다/),
    ).toBeInTheDocument();
  });

  it("a11y 위반 없음", async () => {
    const { container } = render(<VoiceRangeMethodPage />);
    await expectNoA11yViolations(container);
  });
});
