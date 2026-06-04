/**
 * StepIndicator 렌더 테스트 (#1722).
 *
 * 현재/총 단계 라벨, 점(dot) 개수·active 매핑, 접근성 라벨을 검증한다.
 */

import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

import { StepIndicator } from "./StepIndicator";
import { expectNoA11yViolations } from "@/lib/test-helpers/a11y";

afterEach(() => {
  cleanup();
});

describe("StepIndicator", () => {
  it("현재/총 단계를 'n/total 단계' 로 노출한다", () => {
    render(<StepIndicator current={1} total={2} />);
    expect(screen.getByText("1/2 단계")).toBeTruthy();
  });

  it("총 단계 수만큼 점을 렌더한다", () => {
    const { container } = render(<StepIndicator current={1} total={3} />);
    const dots = container.querySelectorAll(
      '[data-testid="step-indicator-dot"]',
    );
    expect(dots).toHaveLength(3);
  });

  it("현재 단계까지의 점만 active 로 표시한다", () => {
    const { container } = render(<StepIndicator current={2} total={3} />);
    const active = container.querySelectorAll(
      '[data-testid="step-indicator-dot"][data-active="true"]',
    );
    const inactive = container.querySelectorAll(
      '[data-testid="step-indicator-dot"][data-active="false"]',
    );
    expect(active).toHaveLength(2);
    expect(inactive).toHaveLength(1);
  });

  it("스크린리더용 aria-label 로 전체/현재 단계를 요약한다", () => {
    render(<StepIndicator current={2} total={2} />);
    expect(screen.getByLabelText("전체 2단계 중 2단계")).toBeTruthy();
  });

  it("a11y 위반(serious/critical) 이 없다", async () => {
    const { container } = render(<StepIndicator current={1} total={2} />);
    await expectNoA11yViolations(container);
  });
});
