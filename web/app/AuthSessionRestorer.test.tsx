/**
 * AuthSessionRestorer 자동로그인 부트스트랩 회귀 가드 (closes #1768).
 *
 * 부수효과 전용 컴포넌트:
 *  - 마운트 시 client.ts Bearer provider 를 등록한다(활성 토큰만 반환).
 *  - 활성 토큰이 있으면 GET /users/me 로 세션복원, 401 이면 로그아웃.
 *  - 토큰이 없으면(익명) fetchMe 미호출.
 *  - 비-401 오류(네트워크 등)는 토큰을 유지한다.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, waitFor } from "@testing-library/react";

import { ApiError } from "@/lib/api/client";

const fetchMeMock = vi.fn();
const setAuthTokenProviderMock = vi.fn();

vi.mock("@/lib/api/auth", () => ({
  fetchMe: (...args: unknown[]) => fetchMeMock(...args),
}));

vi.mock("@/lib/api/client", async () => {
  const actual =
    await vi.importActual<typeof import("@/lib/api/client")>("@/lib/api/client");
  return {
    ...actual,
    setAuthTokenProvider: (...args: unknown[]) => setAuthTokenProviderMock(...args),
  };
});

import { AuthSessionRestorer } from "./AuthSessionRestorer";
import { isTokenActive, useAuthStore } from "@/store/auth";

const FUTURE = "2999-01-01T00:00:00";
const PAST = "2000-01-01T00:00:00";

beforeEach(() => {
  fetchMeMock.mockReset();
  // 기본값 — 명시 override 없으면 resolve 된 promise 를 반환해 .catch 체인이 살아 있게 한다.
  fetchMeMock.mockResolvedValue({ userId: 1, email: "a@b.com" });
  setAuthTokenProviderMock.mockReset();
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

describe("AuthSessionRestorer", () => {
  it("마운트 시 Bearer provider 를 등록한다(활성 토큰만 반환)", () => {
    useAuthStore.setState({ token: "tok", tokenExpiresAt: FUTURE });
    render(<AuthSessionRestorer />);

    expect(setAuthTokenProviderMock).toHaveBeenCalledTimes(1);
    const provider = setAuthTokenProviderMock.mock.calls[0][0] as () => string | null;
    expect(provider()).toBe("tok");

    // 만료 토큰이면 provider 가 null 을 반환한다.
    useAuthStore.setState({ token: "tok", tokenExpiresAt: PAST });
    expect(provider()).toBeNull();
    // isTokenActive 와 동일 판정.
    expect(isTokenActive(PAST)).toBe(false);
  });

  it("활성 토큰이 있으면 fetchMe 로 세션을 복원한다", async () => {
    useAuthStore.setState({ token: "tok-active", tokenExpiresAt: FUTURE });
    fetchMeMock.mockResolvedValueOnce({ userId: 1, email: "a@b.com" });

    render(<AuthSessionRestorer />);

    await waitFor(() => {
      expect(fetchMeMock).toHaveBeenCalledTimes(1);
    });
    expect(fetchMeMock.mock.calls[0][0]).toBe("tok-active");
    // 성공 시 토큰 유지.
    expect(useAuthStore.getState().token).toBe("tok-active");
  });

  it("토큰이 없으면(익명) fetchMe 를 호출하지 않는다", () => {
    render(<AuthSessionRestorer />);
    expect(fetchMeMock).not.toHaveBeenCalled();
  });

  it("fetchMe 가 401 이면 로그아웃 처리한다(stale 토큰 정리)", async () => {
    useAuthStore.setState({
      token: "tok-stale",
      tokenExpiresAt: FUTURE,
      userId: 2,
      email: "x@y.com",
    });
    fetchMeMock.mockRejectedValueOnce(new ApiError(401, "unauthorized", null));

    render(<AuthSessionRestorer />);

    await waitFor(() => {
      expect(useAuthStore.getState().token).toBeNull();
    });
    expect(useAuthStore.getState().userId).toBeNull();
  });

  it("비-401 오류(네트워크 등)는 토큰을 유지한다", async () => {
    useAuthStore.setState({ token: "tok-keep", tokenExpiresAt: FUTURE });
    fetchMeMock.mockRejectedValueOnce(new TypeError("Failed to fetch"));

    render(<AuthSessionRestorer />);

    await waitFor(() => {
      expect(fetchMeMock).toHaveBeenCalledTimes(1);
    });
    expect(useAuthStore.getState().token).toBe("tok-keep");
  });
});
