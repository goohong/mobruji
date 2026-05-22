/**
 * BottomNav 컴포넌트 단위 테스트.
 *
 * 검증:
 *   - 5개 탭이 모두 렌더된다 (홈/측정/추천/이력/북마크).
 *   - 현재 경로에 해당하는 탭에 aria-current="page"가 붙는다.
 *   - 자식 경로(/voice-range/auto)에서 "측정" 탭이 활성된다.
 *   - 홈("/")은 정확 매치만 — 다른 경로에서 홈이 활성되지 않는다.
 *   - isActive 유틸 단위 케이스.
 *
 * usePathname mock:
 *   - next/navigation을 vi.mock으로 가짜화. 테스트마다 path를 바꿔 active 매치 검증.
 */

import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

import { BottomNav, isActive } from "./BottomNav";

const pathnameMock = vi.fn<() => string>();

vi.mock("next/navigation", () => ({
  usePathname: () => pathnameMock(),
}));

afterEach(() => {
  cleanup();
  pathnameMock.mockReset();
});

describe("BottomNav", () => {
  it("5개 탭(홈/측정/추천/이력/북마크)을 렌더한다", () => {
    pathnameMock.mockReturnValue("/");
    render(<BottomNav />);

    expect(screen.getByRole("link", { name: "홈" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "측정" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "추천" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "이력" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "북마크" })).toBeInTheDocument();
  });

  it("현재 경로가 / 면 홈 탭만 aria-current=page", () => {
    pathnameMock.mockReturnValue("/");
    render(<BottomNav />);

    expect(screen.getByRole("link", { name: "홈" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "추천" })).not.toHaveAttribute("aria-current");
  });

  it("자식 경로(/voice-range/auto)에서 측정 탭이 활성된다", () => {
    pathnameMock.mockReturnValue("/voice-range/auto");
    render(<BottomNav />);

    expect(screen.getByRole("link", { name: "측정" })).toHaveAttribute("aria-current", "page");
    // 홈은 prefix 매치 제외 규칙으로 비활성이어야 한다 — 회귀 가드.
    expect(screen.getByRole("link", { name: "홈" })).not.toHaveAttribute("aria-current");
  });

  it("/recommend 경로에서 추천 탭만 활성된다", () => {
    pathnameMock.mockReturnValue("/recommend");
    render(<BottomNav />);

    expect(screen.getByRole("link", { name: "추천" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "측정" })).not.toHaveAttribute("aria-current");
    expect(screen.getByRole("link", { name: "이력" })).not.toHaveAttribute("aria-current");
  });

  it("nav 요소가 모바일 한정(md:hidden) 클래스를 가진다", () => {
    pathnameMock.mockReturnValue("/");
    render(<BottomNav />);

    const nav = screen.getByRole("navigation", { name: "주요 메뉴" });
    expect(nav.className).toContain("md:hidden");
  });
});

describe("isActive", () => {
  it("홈은 정확 매치만 true", () => {
    expect(isActive("/", "/")).toBe(true);
    expect(isActive("/recommend", "/")).toBe(false);
    expect(isActive("/voice-range/auto", "/")).toBe(false);
  });

  it("일반 경로는 정확 매치 또는 자식 prefix 매치 true", () => {
    expect(isActive("/recommend", "/recommend")).toBe(true);
    expect(isActive("/recommend/123", "/recommend")).toBe(true);
    expect(isActive("/recommendation", "/recommend")).toBe(false);
  });

  it("형제 경로는 매치되지 않는다", () => {
    expect(isActive("/likes", "/bookmarks")).toBe(false);
  });
});
