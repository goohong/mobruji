/**
 * RecommendRefinePanel 테스트 (recommend-page-visual-ux-audit-1708 V1·V2·V5).
 *
 * 검증 포인트:
 *   1) 기본은 접힌 상태 — 의도/분위기/나이대 컨트롤이 노출되지 않는다(결과 우선).
 *   2) "추천 다듬기" 바를 누르면 펼쳐져 의도 모드 + 필터 + 안내문이 노출된다.
 *   3) 활성 조건 개수가 접힌 바에 배지로 표시된다.
 *   4) a11y 위반 없음(펼친 상태).
 */

import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { expectNoA11yViolations } from "@/lib/test-helpers/a11y";

import { RecommendRefinePanel } from "./RecommendRefinePanel";

afterEach(() => cleanup());

function renderPanel(
  overrides: Partial<Parameters<typeof RecommendRefinePanel>[0]> = {},
) {
  const props = {
    selectedPersona: null,
    onPersonaChange: vi.fn(),
    selectedMood: null,
    selectedAgeGroup: null,
    onMoodChange: vi.fn(),
    onAgeGroupChange: vi.fn(),
    ...overrides,
  };
  render(<RecommendRefinePanel {...props} />);
  return props;
}

describe("RecommendRefinePanel", () => {
  it("기본은 접힌 상태 — 의도/분위기 컨트롤이 노출되지 않는다", () => {
    renderPanel();
    const toggle = screen.getByRole("button", { name: /추천 다듬기/ });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(
      screen.queryByRole("button", { name: /안 망할 곡 추천받기/ }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "감성적인" }),
    ).not.toBeInTheDocument();
  });

  it("바를 누르면 펼쳐져 의도 모드 + 필터 + 안내문이 노출된다", async () => {
    const user = userEvent.setup();
    renderPanel();

    await user.click(screen.getByRole("button", { name: /추천 다듬기/ }));

    expect(
      screen.getByRole("button", { name: /안 망할 곡 추천받기/ }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "감성적인" }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/아무것도 안 골라도 음역대만으로 추천됩니다/),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /추천 다듬기/ }),
    ).toHaveAttribute("aria-expanded", "true");
  });

  it("활성 조건 개수가 접힌 바에 배지로 표시된다", () => {
    renderPanel({ selectedPersona: "P-E", selectedMood: "EMOTIONAL" });
    expect(screen.getByTestId("refine-active-count")).toHaveTextContent("2");
  });

  it("활성 조건이 없으면 개수 배지가 없다", () => {
    renderPanel();
    expect(screen.queryByTestId("refine-active-count")).not.toBeInTheDocument();
  });

  it("a11y 위반이 없다 (펼친 상태)", async () => {
    const user = userEvent.setup();
    const { container } = render(
      <RecommendRefinePanel
        selectedPersona={null}
        onPersonaChange={vi.fn()}
        selectedMood={null}
        selectedAgeGroup={null}
        onMoodChange={vi.fn()}
        onAgeGroupChange={vi.fn()}
      />,
    );
    await user.click(screen.getByRole("button", { name: /추천 다듬기/ }));
    await expectNoA11yViolations(container);
  });
});
