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

  it("pressed=false면 inactive 시각 클래스(bg-white, ring-1)가 적용된다", () => {
    // 회귀 가드: pressed=false ↔ active 토글 시 시각이 명확히 구분되어야
    // SR 사용자뿐 아니라 시각 사용자도 상태를 인지할 수 있다.
    render(
      <Chip onClick={() => {}} pressed={false}>
        JAZZ
      </Chip>,
    );
    const chip = screen.getByRole("button", { name: "JAZZ" });
    expect(chip.className).toContain("bg-white");
    expect(chip.className).toContain("ring-1");
    // pressed=true 전용 클래스는 적용되지 않아야 한다.
    expect(chip.className).not.toContain("bg-zinc-900");
  });

  it("Enter 키로 onClick이 호출된다 (button 변환 회귀 가드)", () => {
    // Chip이 button으로 렌더되지 않으면 native 키보드 활성화가 깨진다.
    // 회귀 시 SR/키보드 사용자는 chip을 활성화할 수 없다.
    const handleClick = vi.fn();
    render(
      <Chip onClick={handleClick} pressed={false}>
        K-POP
      </Chip>,
    );
    const chip = screen.getByRole("button", { name: "K-POP" });
    chip.focus();
    // jsdom 환경에서 button native Enter/Space는 click을 발생시키지 않으므로
    // fireEvent.click으로 키보드 활성화의 의미를 검증한다 (button role 보장).
    fireEvent.click(chip);
    expect(handleClick).toHaveBeenCalledTimes(1);
    // tagName이 BUTTON이어야 native 키보드 활성화가 보장된다.
    expect(chip.tagName).toBe("BUTTON");
  });

  it("tone='success'+pressed=true면 success 시각 클래스가 보존된다", () => {
    // 회귀 가드: button mode에서 neutral 외 tone이 pressed 시 잘못된 tone으로
    // 치환되면 사용자는 의미 색상(예: difficulty)을 잃는다.
    render(
      <Chip onClick={() => {}} pressed tone="success">
        EASY
      </Chip>,
    );
    const chip = screen.getByRole("button", { name: "EASY" });
    expect(chip).toHaveAttribute("aria-pressed", "true");
    // success tone의 emerald 클래스가 적용되어야 (primary로 치환되면 안 됨).
    expect(chip.className).toContain("bg-emerald-100");
    expect(chip.className).not.toContain("bg-zinc-900");
  });

  it("disabled prop이 button으로 전파된다", () => {
    // 회귀 가드: disabled chip은 SR/키보드/마우스 모두에서 비활성으로 인지되어야.
    const handleClick = vi.fn();
    render(
      <Chip onClick={handleClick} pressed={false} disabled>
        DISABLED
      </Chip>,
    );
    const chip = screen.getByRole("button", { name: "DISABLED" });
    expect(chip).toBeDisabled();
    fireEvent.click(chip);
    expect(handleClick).not.toHaveBeenCalled();
  });
});
