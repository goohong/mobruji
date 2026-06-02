/**
 * RouteTransition 단위 테스트 (#1493).
 *
 * 검증:
 *   - children 을 그대로 렌더한다 (전환 래퍼가 콘텐츠를 삼키지 않는다).
 *   - 래퍼에 `animate-fade-in` + grow 체인(`flex flex-1 flex-col`)이 적용된다.
 *   - pathname 이 바뀌면 래퍼 key 가 바뀌어 remount 되며 fade 가 재실행된다.
 *
 * usePathname mock: next/navigation 을 vi.mock 으로 가짜화.
 */

import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

import { RouteTransition } from "./RouteTransition";

const pathnameMock = vi.fn<() => string>();

vi.mock("next/navigation", () => ({
  usePathname: () => pathnameMock(),
}));

afterEach(() => {
  cleanup();
  pathnameMock.mockReset();
});

describe("RouteTransition", () => {
  it("children 을 그대로 렌더한다", () => {
    pathnameMock.mockReturnValue("/");
    render(
      <RouteTransition>
        <p>본문</p>
      </RouteTransition>,
    );

    expect(screen.getByText("본문")).toBeInTheDocument();
  });

  it("래퍼에 fade-in + grow 체인 클래스가 적용된다", () => {
    pathnameMock.mockReturnValue("/recommend");
    render(
      <RouteTransition>
        <p>본문</p>
      </RouteTransition>,
    );

    const wrapper = screen.getByText("본문").parentElement;
    expect(wrapper?.className).toContain("animate-fade-in");
    expect(wrapper?.className).toContain("flex-1");
    expect(wrapper?.className).toContain("flex-col");
  });

  it("pathname 이 바뀌면 래퍼가 remount 된다 (전환 fade 재실행)", () => {
    pathnameMock.mockReturnValue("/");
    const { rerender } = render(
      <RouteTransition>
        <p>본문</p>
      </RouteTransition>,
    );
    const before = screen.getByText("본문").parentElement;

    pathnameMock.mockReturnValue("/history");
    rerender(
      <RouteTransition>
        <p>본문</p>
      </RouteTransition>,
    );
    const after = screen.getByText("본문").parentElement;

    // key 변경으로 DOM 노드가 교체되어 새 fade 애니메이션이 시작된다.
    expect(before).not.toBe(after);
  });
});
