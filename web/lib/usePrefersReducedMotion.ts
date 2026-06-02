/**
 * `prefers-reduced-motion: reduce` 미디어 쿼리를 구독하는 훅 (#1489).
 *
 * CSS 전역 가드(globals.css / tokens.css)는 keyframe·transition 을 끄지만,
 * JS 로 구동하는 제스처(스와이프 덱)는 transform 을 직접 계산하므로 런타임에서도
 * 사용자 선호를 알아야 한다.
 *
 * `useSyncExternalStore` 로 외부(matchMedia)를 구독한다 — effect 안 setState 없이
 * React 권장 방식으로 외부 상태를 읽는다(set-state-in-effect 룰 회피):
 *   - SSR snapshot 은 `false`(모션 허용) — 서버/첫 클라이언트 렌더 일치로 hydration 안전.
 *   - 사용자가 OS 설정을 바꾸면 `change` 이벤트로 즉시 반영.
 *   - `matchMedia` 미지원 환경에서는 안전하게 `false`.
 */

"use client";

import { useSyncExternalStore } from "react";

const QUERY = "(prefers-reduced-motion: reduce)";

function subscribe(onChange: () => void): () => void {
  if (typeof window === "undefined" || typeof window.matchMedia !== "function") {
    return () => {};
  }
  const mediaQuery = window.matchMedia(QUERY);
  mediaQuery.addEventListener("change", onChange);
  return () => {
    mediaQuery.removeEventListener("change", onChange);
  };
}

function getSnapshot(): boolean {
  if (typeof window === "undefined" || typeof window.matchMedia !== "function") {
    return false;
  }
  return window.matchMedia(QUERY).matches;
}

function getServerSnapshot(): boolean {
  return false;
}

export function usePrefersReducedMotion(): boolean {
  return useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot);
}
