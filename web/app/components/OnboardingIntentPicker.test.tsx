/**
 * OnboardingIntentPicker 테스트 (first-user-onboarding-flow.md §7 fe 단위).
 *
 * 검증:
 *  - 3 페르소나 카드 + 보조 경로(둘러보기 / 직접 입력) 노출 + 라우팅 목적지 매핑.
 *  - 카드 클릭 → onboarding store 에 경로 기록(중간 재진입 대비).
 *  - 둘러보기/직접 입력 클릭 → entryPath null 유지 + step 전이.
 *  - a11y 위반 0.
 */

import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { OnboardingIntentPicker } from "./OnboardingIntentPicker";
import { useOnboardingStore } from "@/store/onboarding";
import { expectNoA11yViolations } from "@/lib/test-helpers/a11y";

function resetStore() {
  useOnboardingStore.getState().reset();
  if (typeof localStorage !== "undefined") {
    localStorage.removeItem("mobruji-onboarding");
  }
}

beforeEach(() => resetStore());
afterEach(() => {
  cleanup();
  resetStore();
});

describe("OnboardingIntentPicker", () => {
  it("3 페르소나 카드·둘러보기는 측정 방식 선택 화면으로, 직접 입력은 /voice-range 로 연결한다", () => {
    render(<OnboardingIntentPicker />);
    expect(
      screen.getByRole("link", { name: /내 목소리부터 알아보기/ }),
    ).toHaveAttribute("href", "/voice-range/method");
    expect(
      screen.getByRole("link", { name: /발성·고음 연습할 곡 찾기/ }),
    ).toHaveAttribute("href", "/voice-range/method");
    expect(
      screen.getByRole("link", { name: /분위기 띄울 곡 찾기/ }),
    ).toHaveAttribute("href", "/voice-range/method");
    expect(
      screen.getByRole("link", { name: /그냥 둘러보기/ }),
    ).toHaveAttribute("href", "/voice-range/method");
    expect(
      screen.getByRole("link", { name: /직접 입력으로 시작/ }),
    ).toHaveAttribute("href", "/voice-range");
  });

  it("BEGINNER 카드 클릭 시 entryPath=BEGINNER + step=MEASURE 로 기록한다", async () => {
    const user = userEvent.setup();
    render(<OnboardingIntentPicker />);
    await user.click(
      screen.getByRole("link", { name: /내 목소리부터 알아보기/ }),
    );
    const state = useOnboardingStore.getState();
    expect(state.entryPath).toBe("BEGINNER");
    expect(state.step).toBe("MEASURE");
  });

  it("MOOD 카드 클릭 시 entryPath=MOOD 로 기록한다", async () => {
    const user = userEvent.setup();
    render(<OnboardingIntentPicker />);
    await user.click(screen.getByRole("link", { name: /분위기 띄울 곡 찾기/ }));
    expect(useOnboardingStore.getState().entryPath).toBe("MOOD");
  });

  it("둘러보기 클릭 시 entryPath 는 null 로 두고 step 만 MEASURE 로 전이한다", async () => {
    const user = userEvent.setup();
    render(<OnboardingIntentPicker />);
    await user.click(screen.getByRole("link", { name: /그냥 둘러보기/ }));
    const state = useOnboardingStore.getState();
    expect(state.entryPath).toBeNull();
    expect(state.step).toBe("MEASURE");
  });

  it("a11y 위반 없음", async () => {
    const { container } = render(<OnboardingIntentPicker />);
    await expectNoA11yViolations(container);
  });
});
