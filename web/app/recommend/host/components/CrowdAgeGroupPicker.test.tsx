/**
 * CrowdAgeGroupPicker 컴포넌트 테스트 (이슈 #1601).
 *
 * - 연령대 칩 6종을 렌더한다.
 * - 다중 선택: 미선택 칩 클릭 시 추가, 선택 칩 클릭 시 제거.
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
    render(<CrowdAgeGroupPicker selected={[]} onChange={vi.fn()} />);
    expect(screen.getByRole("button", { name: "10대" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "60대+" })).toBeInTheDocument();
  });

  it("미선택 칩 클릭 시 selected 에 추가해 onChange 가 호출된다", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<CrowdAgeGroupPicker selected={["TWENTIES"]} onChange={onChange} />);

    await user.click(screen.getByRole("button", { name: "30대" }));
    expect(onChange).toHaveBeenCalledWith(["TWENTIES", "THIRTIES"]);
  });

  it("이미 선택된 칩 클릭 시 selected 에서 제거해 onChange 가 호출된다", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(
      <CrowdAgeGroupPicker
        selected={["TWENTIES", "THIRTIES"]}
        onChange={onChange}
      />,
    );

    await user.click(screen.getByRole("button", { name: "20대" }));
    expect(onChange).toHaveBeenCalledWith(["THIRTIES"]);
  });

  it("선택 상태가 aria-pressed 로 노출된다", () => {
    render(<CrowdAgeGroupPicker selected={["THIRTIES"]} onChange={vi.fn()} />);
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
      <CrowdAgeGroupPicker selected={["TWENTIES"]} onChange={vi.fn()} />,
    );
    await expectNoA11yViolations(container);
  });
});
