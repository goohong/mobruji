"use client";

/**
 * 홈 계정 진입점 (closes #1800).
 *
 * - 비로그인: "로그인 / 회원가입" 링크를 노출한다(가입은 선택 — 익명으로도 모든 기능
 *   사용 가능). 진입점이 없으면 새로 추가한 /login·/signup 화면에 도달할 길이 없다.
 * - 로그인: 가입한 이메일 + "로그아웃" 버튼을 노출해 현재 로그인 상태를 확인하고
 *   로그아웃(유일한 토큰 제거 경로)할 수 있게 한다. 재방문 시 AuthSessionRestorer 가
 *   자동로그인한 결과(이메일)도 이 영역에 그대로 반영된다.
 *
 * auth store 는 localStorage 에서 hydrate 되므로, SSR/CSR mismatch 를 피하려고
 * 마운트(hydration) 전에는 비로그인 변형을 렌더한다(홈 page.tsx 의 세션 hydration
 * 가드와 동일 패턴).
 */

import { useSyncExternalStore } from "react";
import Link from "next/link";

import { isTokenActive, useAuthStore } from "@/store/auth";

function useHasHydratedAuth(): boolean {
  return useSyncExternalStore(
    (callback) => useAuthStore.persist.onFinishHydration(callback),
    () => useAuthStore.persist.hasHydrated(),
    () => false,
  );
}

export function HomeAccountNav() {
  const hasHydrated = useHasHydratedAuth();
  const email = useAuthStore((state) => state.email);
  const tokenExpiresAt = useAuthStore((state) => state.tokenExpiresAt);
  const logout = useAuthStore((state) => state.logout);

  const isLoggedIn =
    hasHydrated && email !== null && isTokenActive(tokenExpiresAt);

  if (isLoggedIn) {
    return (
      <section
        aria-label="계정"
        className="flex w-full items-center justify-between gap-3 rounded-[var(--radius-lg)] bg-[var(--bg-base)] px-4 py-3 shadow-[var(--shadow-sm)] ring-1 ring-[var(--border)]"
      >
        <span className="min-w-0 truncate text-sm text-[var(--text-secondary)]">
          <span className="font-medium text-[var(--text-primary)]">{email}</span>{" "}
          님으로 로그인됨
        </span>
        <button
          type="button"
          onClick={logout}
          className="inline-flex h-9 flex-shrink-0 items-center justify-center rounded-full border border-[var(--cta-secondary-border)] bg-[var(--cta-secondary-bg)] px-4 text-sm font-medium text-[var(--cta-secondary-fg)] transition-colors hover:bg-[var(--cta-secondary-bg-hover)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)]"
        >
          로그아웃
        </button>
      </section>
    );
  }

  return (
    <nav aria-label="계정" className="w-full">
      <ul className="grid grid-cols-2 gap-2">
        <li>
          <Link
            href="/login"
            className="inline-flex h-11 w-full items-center justify-center rounded-full border border-[var(--border-input)] bg-[var(--cta-secondary-bg)] px-3 text-sm font-medium text-[var(--cta-secondary-fg)] transition-colors hover:bg-[var(--cta-secondary-bg-hover)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)]"
          >
            로그인
          </Link>
        </li>
        <li>
          <Link
            href="/signup"
            className="inline-flex h-11 w-full items-center justify-center rounded-full border border-[var(--border-input)] bg-[var(--cta-secondary-bg)] px-3 text-sm font-medium text-[var(--cta-secondary-fg)] transition-colors hover:bg-[var(--cta-secondary-bg-hover)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)]"
          >
            회원가입
          </Link>
        </li>
      </ul>
    </nav>
  );
}
