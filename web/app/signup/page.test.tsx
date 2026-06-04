/**
 * 회원가입 페이지 테스트 (closes #1800).
 *
 * 범위:
 *  - 제출 성공: signup 호출 → auth store 에 세션 영속(setSession) → router.push("/onboarding").
 *  - 409(이메일 중복): 사용자 친화 에러 노출 + 라우팅/세션 영속 없음.
 *  - 로그인 진입 링크 노출.
 */

import { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import SignupPage from "./page";
import { ApiError } from "@/lib/api/client";
import { signup } from "@/lib/api/auth";
import { useAuthStore } from "@/store/auth";

const { pushMock } = vi.hoisted(() => ({ pushMock: vi.fn() }));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock }),
}));

vi.mock("@/lib/api/auth", async () => {
  const actual =
    await vi.importActual<typeof import("@/lib/api/auth")>("@/lib/api/auth");
  return { ...actual, signup: vi.fn() };
});

const signupMock = vi.mocked(signup);

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
  await user.type(screen.getByLabelText("이메일"), "new@b.com");
  await user.type(screen.getByLabelText("비밀번호"), "secret123");
  await user.click(screen.getByRole("button", { name: "회원가입" }));
}

beforeEach(() => {
  signupMock.mockReset();
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

describe("SignupPage", () => {
  it("성공 시 signup 호출 → 세션 영속 → /onboarding 으로 라우팅한다", async () => {
    const user = userEvent.setup();
    signupMock.mockResolvedValueOnce({
      userId: 11,
      email: "new@b.com",
      token: "tok-signup",
      tokenExpiresAt: "2999-01-01T00:00:00",
    });

    renderWithQueryClient(<SignupPage />);
    await fillAndSubmit(user);

    await waitFor(() => {
      expect(signupMock).toHaveBeenCalledWith({
        email: "new@b.com",
        password: "secret123",
      });
    });
    await waitFor(() => {
      expect(useAuthStore.getState().token).toBe("tok-signup");
    });
    expect(useAuthStore.getState().email).toBe("new@b.com");
    expect(pushMock).toHaveBeenCalledWith("/onboarding");
  });

  it("409 면 친화 에러를 노출하고 라우팅/영속하지 않는다", async () => {
    const user = userEvent.setup();
    signupMock.mockRejectedValueOnce(
      new ApiError(409, "email exists", { message: "email exists" }),
    );

    renderWithQueryClient(<SignupPage />);
    await fillAndSubmit(user);

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("이미 가입된 이메일입니다");
    expect(pushMock).not.toHaveBeenCalled();
    expect(useAuthStore.getState().token).toBeNull();
  });

  it("로그인 진입 링크가 /login 으로 노출된다", () => {
    renderWithQueryClient(<SignupPage />);
    expect(screen.getByRole("link", { name: "로그인" })).toHaveAttribute(
      "href",
      "/login",
    );
  });
});
