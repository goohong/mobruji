/**
 * Chip 컴포넌트 단위 테스트.
 */

import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

import { Chip } from "./Chip";

afterEach(() => {
  cleanup();
});

describe("Chip", () => {
  it("onClick이 없으면 span으로 렌더된다 (정적 라벨)", () => {
    render(
      <Chip tone="success" data-testid="chip">
        EASY
      </Chip>,
    );
    const chip = screen.getByTestId("chip");
    expect(chip.tagName).toBe("SPAN");
    expect(chip.textContent).toBe("EASY");
    expect(chip.className).toContain("bg-emerald-100");
  });

  it("onClick이 있으면 button으로 렌더되고 클릭이 동작한다", () => {
    const handleClick = vi.fn();
    render(
      <Chip onClick={handleClick} pressed={false}>
        POP
      </Chip>,
    );
    const chip = screen.getByRole("button", { name: "POP" });
    expect(chip).toHaveAttribute("aria-pressed", "false");
    fireEvent.click(chip);
    expect(handleClick).toHaveBeenCalledTimes(1);
  });

  it("pressed=true면 aria-pressed=true와 primary 톤이 적용된다", () => {
    render(
      <Chip onClick={() => {}} pressed>
        ROCK
      </Chip>,
    );
    const chip = screen.getByRole("button", { name: "ROCK" });
    expect(chip).toHaveAttribute("aria-pressed", "true");
    expect(chip.className).toContain("bg-zinc-900");
  });
});
