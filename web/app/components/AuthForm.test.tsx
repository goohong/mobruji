/**
 * AuthForm 단위 테스트 (closes #1800).
 *
 * 범위:
 *  - 이메일/비밀번호 입력 + 제출 시 onSubmit 에 값이 전달되는지.
 *  - loading 시 버튼 disabled + aria-busy + submitting 라벨.
 *  - error 가 role="alert" 로 노출되는지.
 *  - a11y 위반 0.
 */

import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { AuthForm } from "./AuthForm";
import { expectNoA11yViolations } from "@/lib/test-helpers/a11y";

function renderForm(overrides: Partial<Parameters<typeof AuthForm>[0]> = {}) {
  const onSubmit = vi.fn();
  const props = {
    heading: "로그인",
    submitLabel: "로그인",
    submittingLabel: "로그인 중...",
    passwordAutoComplete: "current-password" as const,
    loading: false,
    error: null,
    onSubmit,
    ...overrides,
  };
  return { onSubmit, ...render(<AuthForm {...props} />) };
}

afterEach(() => {
  cleanup();
});

describe("AuthForm", () => {
  it("heading 과 제출 버튼을 렌더한다", () => {
    renderForm();
    expect(screen.getByRole("heading", { name: "로그인" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "로그인" })).toBeEnabled();
  });

  it("제출 시 입력한 이메일·비밀번호를 onSubmit 에 전달한다", async () => {
    const user = userEvent.setup();
    const { onSubmit } = renderForm();

    await user.type(screen.getByLabelText("이메일"), "a@b.com");
    await user.type(screen.getByLabelText("비밀번호"), "secret123");
    await user.click(screen.getByRole("button", { name: "로그인" }));

    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(onSubmit).toHaveBeenCalledWith({
      email: "a@b.com",
      password: "secret123",
    });
  });

  it("loading 시 버튼이 disabled + aria-busy + submitting 라벨로 reflect 된다", () => {
    renderForm({ loading: true });
    const submit = screen.getByRole("button", { name: "로그인 중..." });
    expect(submit).toBeDisabled();
    expect(submit).toHaveAttribute("aria-busy", "true");
  });

  it("error 가 role=alert 로 노출된다", () => {
    renderForm({ error: "이메일 또는 비밀번호가 올바르지 않습니다." });
    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("이메일 또는 비밀번호가 올바르지 않습니다.");
  });

  it("a11y 위반이 없다", async () => {
    const { container } = renderForm({ error: "오류" });
    await expectNoA11yViolations(container);
  });
});
