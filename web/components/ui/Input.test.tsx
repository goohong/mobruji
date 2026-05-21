/**
 * Input 컴포넌트 단위 테스트.
 */

import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

import { Input } from "./Input";

afterEach(() => {
  cleanup();
});

describe("Input", () => {
  it("label과 input이 htmlFor/id로 연결된다", () => {
    render(<Input label="이름" placeholder="홍길동" />);

    const input = screen.getByLabelText("이름") as HTMLInputElement;
    expect(input).toBeInTheDocument();
    expect(input.getAttribute("placeholder")).toBe("홍길동");
  });

  it("error 메시지를 노출하고 aria-invalid + aria-describedby로 연결한다", () => {
    render(<Input label="이메일" error="형식이 올바르지 않아요" />);

    const input = screen.getByLabelText("이메일");
    expect(input).toHaveAttribute("aria-invalid", "true");
    const describedBy = input.getAttribute("aria-describedby");
    expect(describedBy).toBeTruthy();

    const errorEl = screen.getByText("형식이 올바르지 않아요");
    expect(describedBy).toContain(errorEl.id);
  });

  it("labelHidden=true면 label은 sr-only 클래스로 시각 숨김된다", () => {
    render(<Input label="검색" labelHidden />);
    const label = screen.getByText("검색");
    expect(label.className).toContain("sr-only");
  });
});
