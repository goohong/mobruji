/**
 * 인증 세션(토큰 + 프로필) 저장소 — 로그인 유지 (closes #1768).
 *
 * - #1605 BE 가 발급한 불투명 토큰(원문 1회 노출, 30일 TTL)을 localStorage 에 영속해
 *   재방문 시 자동 Authorization Bearer 첨부 → GET /users/me 세션복원(자동로그인)에 쓴다.
 * - 토큰은 **로그아웃(logout) 시에만** 제거한다. 새로고침/재방문은 토큰을 유지한다.
 * - 익명 흐름([[session]])과 독립 — 토큰이 없어도 익명 sessionId 기반 추천은 그대로 동작한다.
 *
 * BE 계약 (UserAuthController / UserProfileController, #1491):
 *   POST /api/v1/users/signup → AuthResponse { userId, email, token, tokenExpiresAt } (201)
 *   POST /api/v1/users/login  → AuthResponse (200)
 *   GET  /api/v1/users/me     → UserProfileResponse (Authorization: Bearer <token>, 401 시 무효)
 */

"use client";

import { create } from "zustand";
import { persist, createJSONStorage } from "zustand/middleware";

/** BE `AuthResponse` 와 동일 필드 — 회원가입/로그인 발급 결과. */
export type AuthSession = {
  userId: number;
  email: string;
  token: string;
  /** BE `LocalDateTime` 직렬화(ISO-8601, 타임존 없음). 예: `2026-07-04T13:37:44`. */
  tokenExpiresAt: string;
};

type AuthState = {
  token: string | null;
  tokenExpiresAt: string | null;
  userId: number | null;
  email: string | null;
  /** 회원가입/로그인 성공 시 발급 세션을 영속한다. */
  setSession: (session: AuthSession) => void;
  /** 로그아웃 — 토큰과 프로필 식별을 모두 제거한다(유일한 토큰 제거 경로). */
  logout: () => void;
};

/**
 * 영속된 만료 시각 기준으로 토큰이 아직 활성인지 판정한다(순수 함수 — 테스트 용이).
 *
 * - `tokenExpiresAt` 이 null/공백/파싱 불가면 비활성으로 본다.
 * - BE 가 타임존 없는 `LocalDateTime` 을 내려주므로 `new Date(...)` 는 로컬 타임존으로
 *   해석한다. 30일 TTL 기준 타임존 오프셋(<=14h)은 만료 판정에 무의미한 수준이라
 *   별도 보정 없이 비교한다.
 */
export function isTokenActive(
  tokenExpiresAt: string | null,
  now: number = Date.now(),
): boolean {
  if (tokenExpiresAt === null || tokenExpiresAt.trim() === "") {
    return false;
  }
  const expiresAtMs = Date.parse(tokenExpiresAt);
  if (Number.isNaN(expiresAtMs)) {
    return false;
  }
  return expiresAtMs > now;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      token: null,
      tokenExpiresAt: null,
      userId: null,
      email: null,
      setSession: (session) =>
        set({
          token: session.token,
          tokenExpiresAt: session.tokenExpiresAt,
          userId: session.userId,
          email: session.email,
        }),
      logout: () =>
        set({ token: null, tokenExpiresAt: null, userId: null, email: null }),
    }),
    {
      name: "mobruji-auth",
      storage: createJSONStorage(() => localStorage),
      // [[session]] 와 동일 — version/migrate 로 스키마 회귀(#1105)를 막는다.
      version: 1,
      migrate: (persisted) => persisted as AuthState,
      /**
       * hydration 시 이미 만료된 토큰은 폐기한다.
       *
       * 만료 토큰을 그대로 들고 GET /users/me 를 호출하면 BE 가 401 을 내므로 어차피
       * 무효다. 미리 비워 두면 자동로그인 단계가 불필요한 401 왕복 없이 곧장 익명/
       * 비로그인 흐름으로 빠진다. 만료가 아니면 그대로 유지(로그아웃 전까지 보존).
       */
      onRehydrateStorage: () => (state) => {
        if (!state) {
          return;
        }
        if (state.token !== null && !isTokenActive(state.tokenExpiresAt)) {
          state.token = null;
          state.tokenExpiresAt = null;
          state.userId = null;
          state.email = null;
        }
      },
    },
  ),
);
