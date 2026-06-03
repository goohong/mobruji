/**
 * Skeleton 컴포넌트 단위 테스트 (#1493).
 */

import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render } from "@testing-library/react";

import { Skeleton } from "./Skeleton";

afterEach(() => {
  cleanup();
});

describe("Skeleton", () => {
  it("shimmer 애니메이션 유틸과 기본 radius 를 입는다", () => {
    const { container } = render(<Skeleton />);
    const block = container.firstElementChild as HTMLElement;
    expect(block.className).toContain("animate-shimmer");
    expect(block.className).toContain("rounded-[var(--radius-md)]");
  });

  it("호출자 className 을 병합한다", () => {
    const { container } = render(<Skeleton className="h-20 w-full" />);
    const block = container.firstElementChild as HTMLElement;
    expect(block.className).toContain("h-20");
    expect(block.className).toContain("w-full");
    expect(block.className).toContain("animate-shimmer");
  });

  it("의미 없는 시각 요소이므로 aria-hidden 으로 스크린리더에서 숨긴다", () => {
    const { container } = render(<Skeleton />);
    const block = container.firstElementChild as HTMLElement;
    expect(block).toHaveAttribute("aria-hidden", "true");
  });

  it("style passthrough 를 적용한다", () => {
    const { container } = render(<Skeleton style={{ width: "42%" }} />);
    const block = container.firstElementChild as HTMLElement;
    expect(block.style.width).toBe("42%");
  });
});
