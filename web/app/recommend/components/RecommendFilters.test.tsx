/**
 * RecommendFilters 컴포넌트 테스트 (directive roadmap-mood-age-ui).
 *
 * 검증 포인트:
 *   1) 분위기 6종 + 나이대 6종 칩을 모두 렌더한다.
 *   2) 칩 클릭 시 해당 enum 값으로 onChange 가 호출된다.
 *   3) 이미 선택된 칩을 다시 누르면 null(해제)로 onChange 가 호출된다.
 *   4) 선택된 칩은 aria-pressed=true 로 노출된다.
 *   5) a11y 위반 없음.
 */

import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { expectNoA11yViolations } from "@/lib/test-helpers/a11y";

import { RecommendFilters } from "./RecommendFilters";

afterEach(() => cleanup());

function renderFilters(
  overrides: Partial<Parameters<typeof RecommendFilters>[0]> = {},
) {
  const props = {
    selectedMood: null,
    selectedAgeGroup: null,
    onMoodChange: vi.fn(),
    onAgeGroupChange: vi.fn(),
    ...overrides,
  };
  render(<RecommendFilters {...props} />);
  return props;
}

describe("RecommendFilters", () => {
  it("분위기 6종 + 나이대 6종 칩을 렌더한다", () => {
    renderFilters();
    for (const label of [
      "신나는",
      "잔잔한",
      "감성적인",
      "파워풀한",
      "그루비한",
      "추억의",
    ]) {
      expect(screen.getByRole("button", { name: label })).toBeInTheDocument();
    }
    for (const label of ["10대", "20대", "30대", "40대", "50대", "60대+"]) {
      expect(screen.getByRole("button", { name: label })).toBeInTheDocument();
    }
  });

  it("분위기 칩 클릭 시 해당 Mood 값으로 onMoodChange 가 호출된다", async () => {
    const user = userEvent.setup();
    const { onMoodChange } = renderFilters();

    await user.click(screen.getByRole("button", { name: "감성적인" }));

    expect(onMoodChange).toHaveBeenCalledWith("EMOTIONAL");
  });

  it("나이대 칩 클릭 시 해당 AgeGroup 값으로 onAgeGroupChange 가 호출된다", async () => {
    const user = userEvent.setup();
    const { onAgeGroupChange } = renderFilters();

    await user.click(screen.getByRole("button", { name: "60대+" }));

    expect(onAgeGroupChange).toHaveBeenCalledWith("SIXTIES_PLUS");
  });

  it("이미 선택된 칩을 다시 누르면 null(해제)로 호출된다", async () => {
    const user = userEvent.setup();
    const { onMoodChange } = renderFilters({ selectedMood: "UPBEAT" });

    await user.click(screen.getByRole("button", { name: "신나는" }));

    expect(onMoodChange).toHaveBeenCalledWith(null);
  });

  it("선택된 칩은 aria-pressed=true 로 노출된다", () => {
    renderFilters({ selectedMood: "CALM", selectedAgeGroup: "TWENTIES" });

    expect(screen.getByRole("button", { name: "잔잔한" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(screen.getByRole("button", { name: "20대" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(screen.getByRole("button", { name: "신나는" })).toHaveAttribute(
      "aria-pressed",
      "false",
    );
  });

  it("a11y 위반이 없다", async () => {
    const { container } = render(
      <RecommendFilters
        selectedMood="POWERFUL"
        selectedAgeGroup={null}
        onMoodChange={vi.fn()}
        onAgeGroupChange={vi.fn()}
      />,
    );
    await expectNoA11yViolations(container);
  });
});
