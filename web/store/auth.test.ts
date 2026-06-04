/**
 * 인증 세션 스토어 단위 테스트 (closes #1768).
 *
 * 범위:
 *  - isTokenActive 만료 판정(순수 함수).
 *  - setSession / logout 동작 + 로그아웃이 유일한 토큰 제거 경로임을 lock.
 *  - persist storage key + 만료 토큰 hydration 자동 폐기.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { isTokenActive, useAuthStore } from "./auth";

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

describe("isTokenActive", () => {
  it("미래 만료 시각이면 활성(true)", () => {
    expect(isTokenActive(FUTURE)).toBe(true);
  });

  it("과거 만료 시각이면 비활성(false)", () => {
    expect(isTokenActive(PAST)).toBe(false);
  });

  it("null / 빈 문자열 / 공백 / 파싱 불가는 비활성(false)", () => {
    expect(isTokenActive(null)).toBe(false);
    expect(isTokenActive("")).toBe(false);
    expect(isTokenActive("   ")).toBe(false);
    expect(isTokenActive("not-a-date")).toBe(false);
  });

  it("주입한 now 기준으로 경계를 판정한다", () => {
    const expiresAt = "2026-06-04T00:00:00";
    const expiresAtMs = Date.parse(expiresAt);
    expect(isTokenActive(expiresAt, expiresAtMs - 1)).toBe(true);
    expect(isTokenActive(expiresAt, expiresAtMs + 1)).toBe(false);
  });
});

describe("useAuthStore.setSession / logout", () => {
  it("setSession 은 발급 세션을 모두 영속한다", () => {
    useAuthStore.getState().setSession({
      userId: 7,
      email: "a@b.com",
      token: "tok-abc",
      tokenExpiresAt: FUTURE,
    });
    const state = useAuthStore.getState();
    expect(state.token).toBe("tok-abc");
    expect(state.tokenExpiresAt).toBe(FUTURE);
    expect(state.userId).toBe(7);
    expect(state.email).toBe("a@b.com");
  });

  it("logout 은 토큰과 프로필 식별을 모두 제거한다", () => {
    useAuthStore.getState().setSession({
      userId: 7,
      email: "a@b.com",
      token: "tok-abc",
      tokenExpiresAt: FUTURE,
    });
    useAuthStore.getState().logout();
    const state = useAuthStore.getState();
    expect(state.token).toBeNull();
    expect(state.tokenExpiresAt).toBeNull();
    expect(state.userId).toBeNull();
    expect(state.email).toBeNull();
  });

  it("persist storage key 는 'mobruji-auth' 로 토큰을 직렬화한다", () => {
    useAuthStore.getState().setSession({
      userId: 1,
      email: "x@y.com",
      token: "tok-persist",
      tokenExpiresAt: FUTURE,
    });
    const raw = localStorage.getItem("mobruji-auth");
    expect(raw).not.toBeNull();
    expect(raw).toContain("tok-persist");
  });
});

/**
 * hydration 시점 만료 토큰 자동 폐기 회귀 가드.
 *
 * 만료 토큰을 들고 GET /users/me 를 호출하면 어차피 401 이므로, hydration 단계에서
 * 미리 비워 불필요한 401 왕복을 막는다. 활성 토큰은 보존(로그아웃 전까지 유지).
 */
describe("useAuthStore persist hydration 만료 토큰 자동 폐기 (#1768)", () => {
  beforeEach(() => {
    if (typeof localStorage !== "undefined") {
      localStorage.clear();
    }
    vi.resetModules();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("hydration 시 만료된(과거) 토큰을 null 화한다", async () => {
    const expiredPayload = {
      state: {
        token: "tok-expired",
        tokenExpiresAt: PAST,
        userId: 3,
        email: "old@user.com",
      },
      version: 1,
    };
    localStorage.setItem("mobruji-auth", JSON.stringify(expiredPayload));

    const { useAuthStore: freshStore } = await import("./auth");
    await freshStore.persist.rehydrate();

    const state = freshStore.getState();
    expect(state.token).toBeNull();
    expect(state.tokenExpiresAt).toBeNull();
    expect(state.userId).toBeNull();
    expect(state.email).toBeNull();
  });

  it("hydration 시 아직 활성인 토큰은 그대로 유지한다", async () => {
    const activePayload = {
      state: {
        token: "tok-active",
        tokenExpiresAt: FUTURE,
        userId: 9,
        email: "live@user.com",
      },
      version: 1,
    };
    localStorage.setItem("mobruji-auth", JSON.stringify(activePayload));

    const { useAuthStore: freshStore } = await import("./auth");
    await freshStore.persist.rehydrate();

    const state = freshStore.getState();
    expect(state.token).toBe("tok-active");
    expect(state.userId).toBe(9);
    expect(state.email).toBe("live@user.com");
  });
});
