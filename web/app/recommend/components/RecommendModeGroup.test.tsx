/**
 * RecommendModeGroup 테스트 (선택형 진입 그룹화 #1712).
 *
 * 검증 포인트:
 *   1) "다른 방식으로 추천받기" 구분 제목 아래 의도 모드 토글 + 호스트 모드 진입을 묶는다.
 *   2) 호스트 모드 진입은 /recommend/host 로 가는 링크이고 "새 화면" 이동 단서를 가진다.
 *   3) 의도 모드 토글 클릭 시 onPersonaChange 가 호출된다(기존 IntentModeToggle 동작 위임).
 *   4) a11y 위반 없음.
 */

import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { expectNoA11yViolations } from "@/lib/test-helpers/a11y";

import { RecommendModeGroup } from "./RecommendModeGroup";

afterEach(() => cleanup());

function renderGroup(
  overrides: Partial<Parameters<typeof RecommendModeGroup>[0]> = {},
) {
  const props = {
    selectedPersona: null,
    onPersonaChange: vi.fn(),
    ...overrides,
  };
  render(<RecommendModeGroup {...props} />);
  return props;
}

describe("RecommendModeGroup", () => {
  it("'다른 방식으로 추천받기' 구분 제목으로 모드 진입을 묶는다", () => {
    renderGroup();
    const section = screen.getByRole("region", {
      name: "다른 방식으로 추천받기",
    });
    expect(
      within(section).getByRole("heading", { name: "다른 방식으로 추천받기" }),
    ).toBeInTheDocument();
    expect(
      within(section).getByRole("button", { name: /안 망할 곡 추천받기/ }),
    ).toBeInTheDocument();
  });

  it("호스트 모드 진입은 /recommend/host 링크이고 '새 화면' 이동 단서를 가진다", () => {
    renderGroup();
    const hostLink = screen.getByRole("link", { name: /모임 사회자 모드/ });
    expect(hostLink).toHaveAttribute("href", "/recommend/host");
    expect(within(hostLink).getByText("새 화면")).toBeInTheDocument();
  });

  it("의도 모드 토글 클릭 시 onPersonaChange 가 호출된다", async () => {
    const user = userEvent.setup();
    const props = renderGroup();

    await user.click(
      screen.getByRole("button", { name: /안 망할 곡 추천받기/ }),
    );

    expect(props.onPersonaChange).toHaveBeenCalledWith("P-E");
  });

  it("a11y 위반이 없다", async () => {
    const { container } = render(
      <RecommendModeGroup selectedPersona={null} onPersonaChange={vi.fn()} />,
    );
    await expectNoA11yViolations(container);
  });
});
