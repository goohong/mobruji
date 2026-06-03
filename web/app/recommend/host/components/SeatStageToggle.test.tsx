/**
 * SeatStageToggle 컴포넌트 테스트 (이슈 #1601).
 *
 * - 3단계(도입/고조/마무리)를 라디오로 렌더하고 곡 수를 노출한다.
 * - 현재 단계가 aria-checked 로 노출된다.
 * - 단계 클릭 시 onSelect 가 해당 단계로 호출된다.
 * - a11y 위반 없음.
 */

import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { expectNoA11yViolations } from "@/lib/test-helpers/a11y";
import type { SequenceStageBundle } from "@/lib/api/recommendation";

import { SeatStageToggle } from "./SeatStageToggle";

afterEach(() => cleanup());

const stages: SequenceStageBundle[] = [
  { stage: "INTRO", songs: [] },
  { stage: "PEAK", songs: [] },
  { stage: "FINALE", songs: [] },
];

describe("SeatStageToggle", () => {
  it("도입/고조/마무리 단계를 라디오로 렌더한다", () => {
    render(
      <SeatStageToggle
        stages={stages}
        currentStage="INTRO"
        onSelect={vi.fn()}
      />,
    );
    expect(screen.getByRole("radio", { name: /도입/ })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: /고조/ })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: /마무리/ })).toBeInTheDocument();
  });

  it("현재 단계가 aria-checked=true 로 노출된다", () => {
    render(
      <SeatStageToggle
        stages={stages}
        currentStage="PEAK"
        onSelect={vi.fn()}
      />,
    );
    expect(screen.getByRole("radio", { name: /고조/ })).toHaveAttribute(
      "aria-checked",
      "true",
    );
    expect(screen.getByRole("radio", { name: /도입/ })).toHaveAttribute(
      "aria-checked",
      "false",
    );
  });

  it("단계 클릭 시 onSelect 가 해당 단계로 호출된다", async () => {
    const user = userEvent.setup();
    const onSelect = vi.fn();
    render(
      <SeatStageToggle
        stages={stages}
        currentStage="INTRO"
        onSelect={onSelect}
      />,
    );

    await user.click(screen.getByRole("radio", { name: /마무리/ }));
    expect(onSelect).toHaveBeenCalledWith("FINALE");
  });

  it("a11y 위반이 없다", async () => {
    const { container } = render(
      <SeatStageToggle
        stages={stages}
        currentStage="INTRO"
        onSelect={vi.fn()}
      />,
    );
    await expectNoA11yViolations(container);
  });
});
