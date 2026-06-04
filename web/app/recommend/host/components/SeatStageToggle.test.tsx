/**
 * SeatStageToggle 컴포넌트 테스트 (이슈 #1601).
 *
 * - 3단계(워밍업/고조/마무리)를 라디오로 렌더하고 곡 수를 노출한다.
 * - 현재 단계가 aria-checked 로 노출된다.
 * - 단계 클릭 시 onSelect 가 해당 단계로 호출된다.
 * - a11y 위반 없음.
 */

import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { expectNoA11yViolations } from "@/lib/test-helpers/a11y";
import type {
  Mood,
  SequenceStage,
  SequenceStageBundle,
} from "@/lib/api/recommendation";

import { SeatStageToggle } from "./SeatStageToggle";

afterEach(() => cleanup());

function bundle(stage: SequenceStage, mood: Mood): SequenceStageBundle {
  return {
    stage,
    mood,
    stageReason: `${stage} 단계`,
    requestId: 1,
    relaxed: false,
    relaxedFilters: [],
    recommendations: [],
  };
}

const stages: SequenceStageBundle[] = [
  bundle("WARMUP", "CALM"),
  bundle("PEAK", "UPBEAT"),
  bundle("CLOSING", "EMOTIONAL"),
];

describe("SeatStageToggle", () => {
  it("워밍업/고조/마무리 단계를 라디오로 렌더한다", () => {
    render(
      <SeatStageToggle
        stages={stages}
        currentStage="WARMUP"
        onSelect={vi.fn()}
      />,
    );
    expect(screen.getByRole("radio", { name: /워밍업/ })).toBeInTheDocument();
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
    expect(screen.getByRole("radio", { name: /워밍업/ })).toHaveAttribute(
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
        currentStage="WARMUP"
        onSelect={onSelect}
      />,
    );

    await user.click(screen.getByRole("radio", { name: /마무리/ }));
    expect(onSelect).toHaveBeenCalledWith("CLOSING");
  });

  it("a11y 위반이 없다", async () => {
    const { container } = render(
      <SeatStageToggle
        stages={stages}
        currentStage="WARMUP"
        onSelect={vi.fn()}
      />,
    );
    await expectNoA11yViolations(container);
  });
});
