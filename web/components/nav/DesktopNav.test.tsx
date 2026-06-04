/**
 * DesktopNav 컴포넌트 단위 테스트 (closes #1717).
 *
 * 검증:
 *   - 측정/추천/이력/북마크 4개 링크 + 브랜드(홈) 링크가 렌더된다.
 *   - 현재 경로에 해당하는 링크에 aria-current="page" 가 붙는다.
 *   - 자식 경로(/voice-range/auto)에서 "측정" 링크가 활성된다.
 *   - 홈("/")에서는 브랜드 홈 링크만 aria-current="page" (정확 매치).
 *   - nav 요소가 데스크톱 한정(md:block) 컨테이너 안에 있고, 모바일 BottomNav 와
 *     구분되는 landmark 라벨을 가진다.
 *
 * usePathname mock: next/navigation 을 vi.mock 으로 가짜화.
 */

import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

import { DesktopNav } from "./DesktopNav";

const pathnameMock = vi.fn<() => string>();

vi.mock("next/navigation", () => ({
  usePathname: () => pathnameMock(),
}));

afterEach(() => {
  cleanup();
  pathnameMock.mockReset();
});

describe("DesktopNav", () => {
  it("측정/추천/이력/북마크 링크와 홈 브랜드 링크를 렌더한다", () => {
    pathnameMock.mockReturnValue("/likes");
    render(<DesktopNav />);

    expect(screen.getByRole("link", { name: "홈" })).toHaveAttribute("href", "/");
    expect(screen.getByRole("link", { name: /측정/ })).toHaveAttribute(
      "href",
      "/voice-range/auto",
    );
    expect(screen.getByRole("link", { name: /추천/ })).toHaveAttribute(
      "href",
      "/recommend",
    );
    expect(screen.getByRole("link", { name: /이력/ })).toHaveAttribute(
      "href",
      "/history",
    );
    expect(screen.getByRole("link", { name: /북마크/ })).toHaveAttribute(
      "href",
      "/bookmarks",
    );
  });

  it("현재 경로(/recommend)의 링크에 aria-current=page 가 붙는다", () => {
    pathnameMock.mockReturnValue("/recommend");
    render(<DesktopNav />);

    expect(screen.getByRole("link", { name: /추천/ })).toHaveAttribute(
      "aria-current",
      "page",
    );
    expect(screen.getByRole("link", { name: /측정/ })).not.toHaveAttribute(
      "aria-current",
    );
  });

  it("자식 경로(/voice-range/auto)에서 측정 링크가 활성된다", () => {
    pathnameMock.mockReturnValue("/voice-range/auto");
    render(<DesktopNav />);

    expect(screen.getByRole("link", { name: /측정/ })).toHaveAttribute(
      "aria-current",
      "page",
    );
    expect(screen.getByRole("link", { name: "홈" })).not.toHaveAttribute(
      "aria-current",
    );
  });

  it("홈('/')에서는 브랜드 홈 링크만 활성된다", () => {
    pathnameMock.mockReturnValue("/");
    render(<DesktopNav />);

    expect(screen.getByRole("link", { name: "홈" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    expect(screen.getByRole("link", { name: /북마크/ })).not.toHaveAttribute(
      "aria-current",
    );
  });

  it("데스크톱 한정(md:block) 컨테이너 + 전용 landmark 라벨을 가진다", () => {
    pathnameMock.mockReturnValue("/");
    render(<DesktopNav />);

    const nav = screen.getByRole("navigation", { name: "주요 메뉴 (데스크톱)" });
    expect(nav).toBeInTheDocument();
    // 고정 헤더 컨테이너가 md:block(데스크톱 노출) 클래스를 가진다.
    const header = nav.closest("header");
    expect(header?.className).toContain("md:block");
    expect(header?.className).toContain("hidden");
  });
});
