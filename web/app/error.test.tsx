/**
 * 전역 에러 boundary fallback 렌더 테스트.
 *
 * Next.js App Router의 error.tsx는 (error, reset)을 props로 받는다.
 * - 친절한 안내 메시지가 노출되고,
 * - "다시 시도" / "홈으로" 두 동선이 보이고,
 * - reset 버튼 클릭 시 reset 콜백이 호출된다.
 */

import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

import ErrorPage from "./error";

afterEach(() => {
  cleanup();
});

describe("ErrorPage (app/error.tsx)", () => {
  it("에러 fallback 메시지와 두 액션을 렌더한다", () => {
    const reset = vi.fn();
    const error = Object.assign(new Error("boom"), { digest: "abc123" });

    render(<ErrorPage error={error} reset={reset} />);

    expect(
      screen.getByRole("heading", { name: /문제가 발생했습니다/ }),
    ).toBeInTheDocument();
    expect(screen.getByText(/ref: abc123/)).toBeInTheDocument();

    const retry = screen.getByRole("button", { name: /다시 시도/ });
    fireEvent.click(retry);
    expect(reset).toHaveBeenCalledTimes(1);

    expect(screen.getByRole("link", { name: /홈으로/ })).toBeInTheDocument();
  });
});
