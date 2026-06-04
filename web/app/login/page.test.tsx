/**
 * 로그인 페이지 테스트 (closes #1800).
 *
 * 범위:
 *  - 제출 성공: login 호출 → auth store 에 세션 영속(setSession) → router.push("/").
 *  - 401(자격 불일치): 사용자 친화 에러 노출 + 라우팅/세션 영속 없음.
 *  - 회원가입 진입 링크 노출.
 *
 * auth store 는 실제 store 를 쓰고(real persist) localStorage 를 매 테스트 초기화한다.
 */

import { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import LoginPage from "./page";
import { ApiError } from "@/lib/api/client";
import { login } from "@/lib/api/auth";
import { useAuthStore } from "@/store/auth";

const { pushMock } = vi.hoisted(() => ({ pushMock: vi.fn() }));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock }),
}));

vi.mock("@/lib/api/auth", async () => {
  const actual =
    await vi.importActual<typeof import("@/lib/api/auth")>("@/lib/api/auth");
  return { ...actual, login: vi.fn() };
});

const loginMock = vi.mocked(login);

function renderWithQueryClient(ui: ReactNode) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  return render(
    <QueryClientProvider client={client}>{ui}</QueryClientProvider>,
  );
}

async function fillAndSubmit(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText("이메일"), "a@b.com");
  await user.type(screen.getByLabelText("비밀번호"), "secret123");
  await user.click(screen.getByRole("button", { name: "로그인" }));
}

beforeEach(() => {
  loginMock.mockReset();
  pushMock.mockReset();
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

describe("LoginPage", () => {
  it("성공 시 login 호출 → 세션 영속 → /로 라우팅한다", async () => {
    const user = userEvent.setup();
    loginMock.mockResolvedValueOnce({
      userId: 7,
      email: "a@b.com",
      token: "tok-login",
      tokenExpiresAt: "2999-01-01T00:00:00",
    });

    renderWithQueryClient(<LoginPage />);
    await fillAndSubmit(user);

    await waitFor(() => {
      expect(loginMock).toHaveBeenCalledWith({
        email: "a@b.com",
        password: "secret123",
      });
    });
    await waitFor(() => {
      expect(useAuthStore.getState().token).toBe("tok-login");
    });
    expect(useAuthStore.getState().email).toBe("a@b.com");
    expect(pushMock).toHaveBeenCalledWith("/");
  });

  it("401 이면 친화 에러를 노출하고 라우팅/영속하지 않는다", async () => {
    const user = userEvent.setup();
    loginMock.mockRejectedValueOnce(
      new ApiError(401, "bad credentials", { message: "bad credentials" }),
    );

    renderWithQueryClient(<LoginPage />);
    await fillAndSubmit(user);

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("이메일 또는 비밀번호가 올바르지 않습니다.");
    expect(pushMock).not.toHaveBeenCalled();
    expect(useAuthStore.getState().token).toBeNull();
  });

  it("회원가입 진입 링크가 /signup 으로 노출된다", () => {
    renderWithQueryClient(<LoginPage />);
    expect(screen.getByRole("link", { name: "회원가입" })).toHaveAttribute(
      "href",
      "/signup",
    );
  });
});
