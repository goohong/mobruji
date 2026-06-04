/**
 * IntentModeToggle 컴포넌트 테스트 (이슈 #1600).
 *
 * 검증 포인트:
 *   1) "안 망할 곡" 의도 모드 토글을 렌더한다.
 *   2) 꺼진 상태에서 누르면 P-E 로 onPersonaChange 가 호출된다.
 *   3) 켜진 상태(P-E)에서 누르면 null(해제)로 호출된다.
 *   4) 선택 상태가 aria-pressed 로 노출된다.
 *   5) a11y 위반 없음.
 */

import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { expectNoA11yViolations } from "@/lib/test-helpers/a11y";

import { IntentModeToggle } from "./IntentModeToggle";

afterEach(() => cleanup());

function renderToggle(
  overrides: Partial<Parameters<typeof IntentModeToggle>[0]> = {},
) {
  const props = {
    selectedPersona: null,
    onPersonaChange: vi.fn(),
    ...overrides,
  };
  render(<IntentModeToggle {...props} />);
  return props;
}

describe("IntentModeToggle", () => {
  it("'안 망할 곡 추천받기' 토글을 렌더한다", () => {
    renderToggle();
    expect(
      screen.getByRole("button", { name: /안 망할 곡 추천받기/ }),
    ).toBeInTheDocument();
  });

  it("꺼진 상태에서 누르면 P-E 로 onPersonaChange 가 호출된다", async () => {
    const user = userEvent.setup();
    const { onPersonaChange } = renderToggle();

    await user.click(
      screen.getByRole("button", { name: /안 망할 곡 추천받기/ }),
    );

    expect(onPersonaChange).toHaveBeenCalledWith("P-E");
  });

  it("켜진 상태(P-E)에서 누르면 null(해제)로 호출된다", async () => {
    const user = userEvent.setup();
    const { onPersonaChange } = renderToggle({ selectedPersona: "P-E" });

    await user.click(
      screen.getByRole("button", { name: /안 망할 곡 추천받기/ }),
    );

    expect(onPersonaChange).toHaveBeenCalledWith(null);
  });

  it("선택 상태가 aria-pressed 로 노출된다", () => {
    renderToggle({ selectedPersona: "P-E" });
    expect(
      screen.getByRole("button", { name: /안 망할 곡 추천받기/ }),
    ).toHaveAttribute("aria-pressed", "true");
  });

  it("미선택 상태는 aria-pressed=false 다", () => {
    renderToggle({ selectedPersona: null });
    expect(
      screen.getByRole("button", { name: /안 망할 곡 추천받기/ }),
    ).toHaveAttribute("aria-pressed", "false");
  });

  it("'고음 질러 박수받기'(P-F) 토글을 렌더한다", () => {
    renderToggle();
    expect(
      screen.getByRole("button", { name: /고음 질러 박수받기/ }),
    ).toBeInTheDocument();
  });

  it("꺼진 상태에서 P-F 를 누르면 P-F 로 onPersonaChange 가 호출된다", async () => {
    const user = userEvent.setup();
    const { onPersonaChange } = renderToggle();

    await user.click(
      screen.getByRole("button", { name: /고음 질러 박수받기/ }),
    );

    expect(onPersonaChange).toHaveBeenCalledWith("P-F");
  });

  it("켜진 상태(P-F)에서 누르면 null(해제)로 호출된다", async () => {
    const user = userEvent.setup();
    const { onPersonaChange } = renderToggle({ selectedPersona: "P-F" });

    await user.click(
      screen.getByRole("button", { name: /고음 질러 박수받기/ }),
    );

    expect(onPersonaChange).toHaveBeenCalledWith(null);
  });

  it("상호 배타 — P-E 가 켜진 상태에서 P-F 를 누르면 P-F 로 전환한다", async () => {
    const user = userEvent.setup();
    const { onPersonaChange } = renderToggle({ selectedPersona: "P-E" });

    await user.click(
      screen.getByRole("button", { name: /고음 질러 박수받기/ }),
    );

    expect(onPersonaChange).toHaveBeenCalledWith("P-F");
  });

  it("P-F 선택 상태에서 P-E 버튼은 aria-pressed=false 다", () => {
    renderToggle({ selectedPersona: "P-F" });
    expect(
      screen.getByRole("button", { name: /안 망할 곡 추천받기/ }),
    ).toHaveAttribute("aria-pressed", "false");
    expect(
      screen.getByRole("button", { name: /고음 질러 박수받기/ }),
    ).toHaveAttribute("aria-pressed", "true");
  });

  it("a11y 위반이 없다", async () => {
    const { container } = render(
      <IntentModeToggle selectedPersona="P-E" onPersonaChange={vi.fn()} />,
    );
    await expectNoA11yViolations(container);
  });
});
