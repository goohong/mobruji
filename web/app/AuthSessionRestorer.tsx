"use client";

/**
 * 로그인 유지 부트스트랩 (closes #1768). 렌더링 없음(부수 효과 전용).
 *
 * 1. client.ts 의 Authorization Bearer provider 를 auth store 와 연결한다 — 이후 모든
 *    apiFetch 가 활성 토큰을 자동 첨부한다(만료 토큰은 첨부하지 않음).
 * 2. 영속된 활성 토큰이 있으면 GET /api/v1/users/me 로 세션을 복원(자동로그인)하고,
 *    토큰이 무효(401)면 로그아웃 처리해 stale 토큰을 정리한다.
 *
 * 토큰이 없으면(익명/비로그인) 아무 것도 하지 않아 익명 흐름과 호환된다.
 */

import { useEffect } from "react";

import { fetchMe } from "@/lib/api/auth";
import { ApiError, setAuthTokenProvider } from "@/lib/api/client";
import { isTokenActive, useAuthStore } from "@/store/auth";

export function AuthSessionRestorer() {
  useEffect(() => {
    setAuthTokenProvider(() => {
      const { token, tokenExpiresAt } = useAuthStore.getState();
      return isTokenActive(tokenExpiresAt) ? token : null;
    });

    const controller = new AbortController();
    const { token, tokenExpiresAt } = useAuthStore.getState();

    if (token === null || !isTokenActive(tokenExpiresAt)) {
      return () => controller.abort();
    }

    void fetchMe(token, { signal: controller.signal }).catch((error: unknown) => {
      // 토큰 만료/폐기 → BE 401. stale 토큰을 정리해 다음 방문이 비로그인으로 시작하게 한다.
      // 네트워크/일시 오류(비-401)는 토큰을 유지한다 — 일시 장애로 로그아웃시키지 않기 위해.
      if (error instanceof ApiError && error.status === 401) {
        useAuthStore.getState().logout();
      }
    });

    return () => controller.abort();
  }, []);

  return null;
}
