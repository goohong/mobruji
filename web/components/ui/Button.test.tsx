/**
 * Button 컴포넌트 단위 테스트.
 *
 * 검증:
 *   - variant/size 클래스가 실제로 붙는다.
 *   - loading=true면 disabled + aria-busy=true.
 *   - 클릭 핸들러가 호출된다.
 */

import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

import { Button } from "./Button";

afterEach(() => {
  cleanup();
});

describe("Button", () => {
  it("variant=primary, size=lg를 적용하고 클릭이 호출된다", () => {
    const handleClick = vi.fn();
    render(
      <Button variant="primary" size="lg" onClick={handleClick}>
        저장
      </Button>,
    );

    const button = screen.getByRole("button", { name: "저장" });
    expect(button).toBeInTheDocument();
    expect(button.className).toContain("h-12");
    expect(button.className).toContain("bg-[var(--cta-neutral-bg)]");
    expect(button.getAttribute("type")).toBe("button");

    fireEvent.click(button);
    expect(handleClick).toHaveBeenCalledTimes(1);
  });

  it("loading=true면 disabled가 되고 aria-busy=true를 노출한다", () => {
    const handleClick = vi.fn();
    render(
      <Button loading onClick={handleClick}>
        저장 중
      </Button>,
    );

    const button = screen.getByRole("button", { name: /저장 중/ });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute("aria-busy", "true");

    fireEvent.click(button);
    expect(handleClick).not.toHaveBeenCalled();
  });

  it("variant=danger와 fullWidth가 클래스에 반영된다", () => {
    render(
      <Button variant="danger" fullWidth>
        삭제
      </Button>,
    );

    const button = screen.getByRole("button", { name: "삭제" });
    expect(button.className).toContain("bg-rose-600");
    expect(button.className).toContain("w-full");
  });
});
