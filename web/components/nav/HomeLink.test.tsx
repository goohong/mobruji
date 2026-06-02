/**
 * HomeLink 컴포넌트 단위 테스트 (#1492).
 *
 * 검증:
 *   - 홈 외 경로에서 루트(/)로 가는 링크를 렌더한다.
 *   - 홈("/") 에서는 중복 회피로 렌더하지 않는다(null).
 *   - 자식 경로(/voice-range/auto)에서도 노출된다.
 *
 * usePathname mock:
 *   - next/navigation 을 vi.mock 으로 가짜화. 테스트마다 path 를 바꿔 노출 분기 검증.
 */

import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

import { HomeLink } from "./HomeLink";

const pathnameMock = vi.fn<() => string>();

vi.mock("next/navigation", () => ({
  usePathname: () => pathnameMock(),
}));

afterEach(() => {
  cleanup();
  pathnameMock.mockReset();
});

describe("HomeLink", () => {
  it("홈 외 경로에서 루트(/)로 가는 링크를 렌더한다", () => {
    pathnameMock.mockReturnValue("/recommend");
    render(<HomeLink />);

    const link = screen.getByRole("link", { name: "홈으로 이동" });
    expect(link).toBeInTheDocument();
    expect(link).toHaveAttribute("href", "/");
  });

  it("홈(/) 에서는 렌더하지 않는다 (중복 회피)", () => {
    pathnameMock.mockReturnValue("/");
    const { container } = render(<HomeLink />);

    expect(container).toBeEmptyDOMElement();
    expect(screen.queryByRole("link", { name: "홈으로 이동" })).not.toBeInTheDocument();
  });

  it("자식 경로(/voice-range/auto)에서도 노출된다", () => {
    pathnameMock.mockReturnValue("/voice-range/auto");
    render(<HomeLink />);

    expect(screen.getByRole("link", { name: "홈으로 이동" })).toBeInTheDocument();
  });

  it("좌상단 floating 클래스를 가진다", () => {
    pathnameMock.mockReturnValue("/recommend");
    render(<HomeLink />);

    const link = screen.getByRole("link", { name: "홈으로 이동" });
    expect(link.className).toContain("fixed");
    expect(link.className).toContain("top-3");
    expect(link.className).toContain("left-3");
  });
});
