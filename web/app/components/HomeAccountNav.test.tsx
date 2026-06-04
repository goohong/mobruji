/**
 * HomeAccountNav 테스트 (closes #1800).
 *
 * 범위:
 *  - 비로그인: 로그인(/login)·회원가입(/signup) 진입 링크 노출.
 *  - 로그인(활성 토큰 + 이메일): 이메일 + 로그아웃 버튼 노출, 클릭 시 logout → 비로그인 변형.
 *  - 만료 토큰은 비로그인으로 취급.
 *
 * auth store 는 실제 store 를 쓰고 localStorage 를 매 테스트 초기화한다(home page.test 패턴).
 */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { HomeAccountNav } from "./HomeAccountNav";
import { useAuthStore } from "@/store/auth";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn() }),
}));

const FUTURE = "2999-01-01T00:00:00";
const PAST = "2000-01-01T00:00:00";

beforeEach(() => {
  if (typeof localStorage !== "undefined") {
    localStorage.clear();
  }
  useAuthStore.setState({
    token: null,
    tokenExpiresAt: null,
    userId: null,
    email: null,
  });
});

afterEach(() => {
  cleanup();
});

describe("HomeAccountNav", () => {
  it("비로그인 시 로그인·회원가입 진입 링크를 노출한다", () => {
    render(<HomeAccountNav />);
    expect(screen.getByRole("link", { name: "로그인" })).toHaveAttribute(
      "href",
      "/login",
    );
    expect(screen.getByRole("link", { name: "회원가입" })).toHaveAttribute(
      "href",
      "/signup",
    );
  });

  it("활성 토큰 + 이메일이면 이메일과 로그아웃 버튼을 노출한다", () => {
    useAuthStore.setState({
      token: "tok",
      tokenExpiresAt: FUTURE,
      userId: 1,
      email: "me@b.com",
    });

    render(<HomeAccountNav />);
    expect(screen.getByText(/me@b\.com/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "로그아웃" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "로그인" })).not.toBeInTheDocument();
  });

  it("로그아웃 버튼 클릭 시 logout 되어 비로그인 변형으로 돌아간다", async () => {
    const user = userEvent.setup();
    useAuthStore.setState({
      token: "tok",
      tokenExpiresAt: FUTURE,
      userId: 1,
      email: "me@b.com",
    });

    render(<HomeAccountNav />);
    await user.click(screen.getByRole("button", { name: "로그아웃" }));

    await waitFor(() => {
      expect(screen.getByRole("link", { name: "로그인" })).toBeInTheDocument();
    });
    expect(useAuthStore.getState().token).toBeNull();
  });

  it("만료 토큰이면 비로그인으로 취급한다", () => {
    useAuthStore.setState({
      token: "tok",
      tokenExpiresAt: PAST,
      userId: 1,
      email: "me@b.com",
    });

    render(<HomeAccountNav />);
    expect(screen.getByRole("link", { name: "로그인" })).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "로그아웃" }),
    ).not.toBeInTheDocument();
  });
});
