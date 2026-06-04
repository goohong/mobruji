/**
 * BrandWordmark 단위 테스트 (ui-ux-redesign 단계 4 PR 8, #1689).
 *
 * 검증:
 *   - lang prop 별 wordmark 텍스트(ko="모부르지" / en="mobruji") 렌더.
 *   - size prop 별 사이즈 클래스 분기.
 *   - theme prop 별 색(auto=--brand-500 / light=--text-primary / dark 고정).
 *   - 기본값(ko / md / auto) 동작.
 *   - 로고 마크 SVG 는 aria-hidden (스크린리더 중복 낭독 회피).
 */

import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

import { BrandWordmark } from "./BrandWordmark";

afterEach(() => {
  cleanup();
});

describe("BrandWordmark", () => {
  it("기본값은 ko 한글 wordmark 를 렌더한다", () => {
    render(<BrandWordmark />);

    expect(screen.getByText("모부르지")).toBeInTheDocument();
    const root = screen.getByTestId("brand-wordmark");
    expect(root.dataset.lang).toBe("ko");
    expect(root.dataset.size).toBe("md");
    expect(root.dataset.theme).toBe("auto");
  });

  it("lang='en' 이면 영문 wordmark 를 렌더한다", () => {
    render(<BrandWordmark lang="en" />);

    expect(screen.getByText("mobruji")).toBeInTheDocument();
    expect(screen.queryByText("모부르지")).not.toBeInTheDocument();
    expect(screen.getByTestId("brand-wordmark").dataset.lang).toBe("en");
  });

  it("size prop 별 사이즈 클래스를 분기한다", () => {
    const { rerender } = render(<BrandWordmark size="sm" />);
    expect(screen.getByText("모부르지").className).toContain("text-base");
    expect(screen.getByTestId("brand-wordmark").dataset.size).toBe("sm");

    rerender(<BrandWordmark size="lg" />);
    expect(screen.getByText("모부르지").className).toContain("text-4xl");
    expect(screen.getByTestId("brand-wordmark").dataset.size).toBe("lg");
  });

  it("theme='auto' 는 브랜드 컬러(--brand-500) 를 사용한다", () => {
    render(<BrandWordmark theme="auto" />);

    const root = screen.getByTestId("brand-wordmark");
    expect(root.dataset.theme).toBe("auto");
    expect(root.style.color).toContain("--brand-500");
  });

  it("theme='light' 는 --text-primary 잉크를 사용한다", () => {
    render(<BrandWordmark theme="light" />);

    const root = screen.getByTestId("brand-wordmark");
    expect(root.dataset.theme).toBe("light");
    expect(root.style.color).toContain("--text-primary");
  });

  it("theme='dark' 는 밝은 잉크를 사용한다", () => {
    render(<BrandWordmark theme="dark" />);

    const root = screen.getByTestId("brand-wordmark");
    expect(root.dataset.theme).toBe("dark");
    expect(root.style.color.toLowerCase()).toContain("#fafafa");
  });

  it("로고 마크 SVG 는 aria-hidden 으로 가려 중복 낭독을 막는다", () => {
    const { container } = render(<BrandWordmark />);

    const svg = container.querySelector("svg");
    expect(svg).not.toBeNull();
    expect(svg).toHaveAttribute("aria-hidden", "true");
  });

  it("추가 className 을 병합한다", () => {
    render(<BrandWordmark className="custom-x" />);
    expect(screen.getByTestId("brand-wordmark").className).toContain("custom-x");
  });
});
