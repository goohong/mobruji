/**
 * RecommendRefinePanel 테스트 (recommend-page-visual-ux-audit-1708 V1·V2·V5,
 * 선택형 진입 그룹화 #1712 — 필터 전용으로 좁힘).
 *
 * 검증 포인트:
 *   1) 기본은 접힌 상태 — 분위기/나이대 필터가 노출되지 않는다(결과 우선).
 *   2) "추천 다듬기" 바를 누르면 펼쳐져 필터 + 안내문이 노출된다.
 *   3) 모드 진입(의도 모드)은 이 패널에 없다 — RecommendModeGroup 으로 분리.
 *   4) 활성 필터 개수가 접힌 바에 배지로 표시된다.
 *   5) a11y 위반 없음(펼친 상태).
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
    selectedMood: null,
    selectedAgeGroup: null,
    onMoodChange: vi.fn(),
    onAgeGroupChange: vi.fn(),
    onClearAll: vi.fn(),
    ...overrides,
  };
  render(<RecommendRefinePanel {...props} />);
  return props;
}

describe("RecommendRefinePanel", () => {
  it("기본은 접힌 상태 — 분위기 필터가 노출되지 않는다", () => {
    renderPanel();
    const toggle = screen.getByRole("button", { name: /추천 다듬기/ });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(
      screen.queryByRole("button", { name: "감성적인" }),
    ).not.toBeInTheDocument();
  });

  it("바를 누르면 펼쳐져 필터 + 안내문이 노출된다", async () => {
    const user = userEvent.setup();
    renderPanel();

    await user.click(screen.getByRole("button", { name: /추천 다듬기/ }));

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

  it("모드 진입(의도 모드)은 이 필터 패널에 없다 — RecommendModeGroup 으로 분리", async () => {
    const user = userEvent.setup();
    renderPanel();

    await user.click(screen.getByRole("button", { name: /추천 다듬기/ }));

    expect(
      screen.queryByRole("button", { name: /안 망할 곡 추천받기/ }),
    ).not.toBeInTheDocument();
  });

  it("활성 필터 개수가 접힌 바에 배지로 표시된다", () => {
    renderPanel({ selectedMood: "EMOTIONAL", selectedAgeGroup: "THIRTIES" });
    expect(screen.getByTestId("refine-active-count")).toHaveTextContent("2");
  });

  it("활성 필터가 없으면 개수 배지가 없다", () => {
    renderPanel();
    expect(screen.queryByTestId("refine-active-count")).not.toBeInTheDocument();
  });

  // closes #1715 — 필터 적용 피드백/해제.
  it("활성 필터가 없으면 '모두 해제'가 노출되지 않는다", () => {
    renderPanel();
    expect(screen.queryByTestId("refine-clear-all")).not.toBeInTheDocument();
  });

  it("활성 필터가 있으면 '모두 해제'가 노출되고 클릭 시 onClearAll 을 호출한다", async () => {
    const user = userEvent.setup();
    const props = renderPanel({
      selectedMood: "EMOTIONAL",
      selectedAgeGroup: "THIRTIES",
    });
    const clear = screen.getByTestId("refine-clear-all");
    expect(clear).toBeInTheDocument();
    await user.click(clear);
    expect(props.onClearAll).toHaveBeenCalledTimes(1);
  });

  it("a11y 위반이 없다 (펼친 상태)", async () => {
    const user = userEvent.setup();
    const { container } = render(
      <RecommendRefinePanel
        selectedMood={null}
        selectedAgeGroup={null}
        onMoodChange={vi.fn()}
        onAgeGroupChange={vi.fn()}
        onClearAll={vi.fn()}
      />,
    );
    await user.click(screen.getByRole("button", { name: /추천 다듬기/ }));
    await expectNoA11yViolations(container);
  });
});
