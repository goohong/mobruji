/**
 * CrowdAgeGroupPicker 컴포넌트 테스트 (이슈 #1601).
 *
 * - 연령대 칩 6종을 렌더한다.
 * - 단일 선택: 미선택 칩 클릭 시 그 값으로, 선택 칩 재클릭 시 해제(null).
 * - 선택 상태가 aria-pressed 로 노출된다.
 * - a11y 위반 없음.
 */

import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { expectNoA11yViolations } from "@/lib/test-helpers/a11y";

import { CrowdAgeGroupPicker } from "./CrowdAgeGroupPicker";

afterEach(() => cleanup());

describe("CrowdAgeGroupPicker", () => {
  it("연령대 칩 6종을 렌더한다", () => {
    render(<CrowdAgeGroupPicker selected={null} onChange={vi.fn()} />);
    expect(screen.getByRole("button", { name: "10대" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "60대+" })).toBeInTheDocument();
  });

  it("미선택 칩 클릭 시 그 값으로 onChange 가 호출된다", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<CrowdAgeGroupPicker selected={null} onChange={onChange} />);

    await user.click(screen.getByRole("button", { name: "30대" }));
    expect(onChange).toHaveBeenCalledWith("THIRTIES");
  });

  it("이미 선택된 칩 재클릭 시 해제(null)로 onChange 가 호출된다", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(
      <CrowdAgeGroupPicker selected="TWENTIES" onChange={onChange} />,
    );

    await user.click(screen.getByRole("button", { name: "20대" }));
    expect(onChange).toHaveBeenCalledWith(null);
  });

  it("선택 상태가 aria-pressed 로 노출된다", () => {
    render(<CrowdAgeGroupPicker selected="THIRTIES" onChange={vi.fn()} />);
    expect(screen.getByRole("button", { name: "30대" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(screen.getByRole("button", { name: "20대" })).toHaveAttribute(
      "aria-pressed",
      "false",
    );
  });

  it("a11y 위반이 없다", async () => {
    const { container } = render(
      <CrowdAgeGroupPicker selected="TWENTIES" onChange={vi.fn()} />,
    );
    await expectNoA11yViolations(container);
  });
});
