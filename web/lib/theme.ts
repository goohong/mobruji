"use client";

/**
 * 다크/라이트 모드 수동 토글 (이슈 #319 C안 — 직접 구현, next-themes 회피).
 *
 * 정책 (fe 25/33/34 외부 lib 회피 패턴 준수):
 * - 3 모드: `"light" | "dark" | "system"`. 기본값 `"system"`.
 * - `system` 일 때만 `prefers-color-scheme` 변화를 자동 추종, 명시적 모드는 OS 와 무관.
 * - 사용자 선택은 `localStorage.mobruji-theme` 에 저장 — 다음 방문에도 유지.
 * - SSR / 정적 페이지에서 hydration 직전에 `<html class="dark">` 가 결정되도록
 *   `THEME_INIT_SCRIPT` 인라인 스크립트를 layout `<head>` 에 주입.
 * - Tailwind v4 darkMode = class. `globals.css`의 `@custom-variant dark`와 1:1 호환.
 *
 * 구현 메모:
 * - hydration 시 setState 호출이 `react-hooks/set-state-in-effect` 룰에 막히므로
 *   `useSyncExternalStore` 로 외부 store(=localStorage + DOM class + matchMedia)를
 *   구독한다 (app/page.tsx `useHasHydratedSession` 와 동일 패턴).
 */

import { useCallback, useEffect, useSyncExternalStore } from "react";

export type ThemeMode = "light" | "dark" | "system";

/** localStorage 키 — 모듈 단일 진실 원천. */
export const THEME_STORAGE_KEY = "mobruji-theme";

/** `<html>` 에 적용되는 다크 모드 클래스. Tailwind v4 dark variant 와 호환. */
export const THEME_DARK_CLASS = "dark";

/**
 * layout `<head>` 에 inline 으로 삽입하는 초기화 스크립트.
 *
 * - hydration 전에 `<html class="dark">` 여부를 결정해 라이트→다크 flash(FOUC) 방지.
 * - localStorage 키가 없거나 `system` 이면 `matchMedia("(prefers-color-scheme: dark)")`
 *   결과로 결정. 그 외엔 저장된 명시적 모드(`"light"` / `"dark"`)를 사용.
 * - try/catch — localStorage 접근 불가(privacy mode) 시에도 안전 fallback.
 */
export const THEME_INIT_SCRIPT = `
(function () {
  try {
    var stored = localStorage.getItem(${JSON.stringify(THEME_STORAGE_KEY)});
    var prefersDark =
      window.matchMedia &&
      window.matchMedia("(prefers-color-scheme: dark)").matches;
    var isDark =
      stored === "dark" ||
      ((stored === null || stored === "system") && prefersDark);
    var root = document.documentElement;
    if (isDark) {
      root.classList.add(${JSON.stringify(THEME_DARK_CLASS)});
    } else {
      root.classList.remove(${JSON.stringify(THEME_DARK_CLASS)});
    }
  } catch (e) {
    // localStorage / matchMedia 미지원 환경 — 기본 라이트.
  }
})();
`;

/**
 * 외부 store(localStorage + DOM class + matchMedia) 변경 구독.
 *
 * 다른 탭에서 모드를 바꾸거나, system 모드에서 OS prefers 가 바뀌면 callback 호출.
 * `useSyncExternalStore` 가 매 호출마다 새로운 unsubscribe 를 받아 정리한다.
 */
function subscribe(callback: () => void): () => void {
  if (typeof window === "undefined") {
    return () => undefined;
  }
  const onStorage = (event: StorageEvent): void => {
    if (event.key === THEME_STORAGE_KEY) {
      callback();
    }
  };
  const mql = window.matchMedia("(prefers-color-scheme: dark)");
  const onPrefersChange = (): void => callback();
  window.addEventListener("storage", onStorage);
  mql.addEventListener("change", onPrefersChange);
  return () => {
    window.removeEventListener("storage", onStorage);
    mql.removeEventListener("change", onPrefersChange);
  };
}

function readStoredMode(): ThemeMode {
  if (typeof window === "undefined") {
    return "system";
  }
  try {
    const stored = window.localStorage.getItem(THEME_STORAGE_KEY);
    if (stored === "light" || stored === "dark" || stored === "system") {
      return stored;
    }
  } catch {
    // 무시 — 기본 system.
  }
  return "system";
}

function resolveIsDark(mode: ThemeMode): boolean {
  if (typeof window === "undefined") {
    return false;
  }
  if (mode === "dark") {
    return true;
  }
  if (mode === "light") {
    return false;
  }
  return (
    typeof window.matchMedia === "function" &&
    window.matchMedia("(prefers-color-scheme: dark)").matches
  );
}

function applyHtmlClass(isDark: boolean): void {
  if (typeof document === "undefined") {
    return;
  }
  const root = document.documentElement;
  if (isDark) {
    root.classList.add(THEME_DARK_CLASS);
  } else {
    root.classList.remove(THEME_DARK_CLASS);
  }
}

function persistMode(mode: ThemeMode): void {
  if (typeof window === "undefined") {
    return;
  }
  try {
    window.localStorage.setItem(THEME_STORAGE_KEY, mode);
  } catch {
    // 무시 — 영속화 실패해도 현재 세션 토글은 동작.
  }
}

/**
 * 현재 mode + 변경 액션을 반환. `useSyncExternalStore` 로 외부 source 구독.
 *
 * SSR snapshot 은 항상 `"system"` — hydration 직후 클라이언트 store 값으로 보정.
 * 시각 변화는 `THEME_INIT_SCRIPT` 가 hydration 전에 이미 끝낸 상태이므로 없음.
 */
export function useTheme(): {
  mode: ThemeMode;
  isDark: boolean;
  setMode: (next: ThemeMode) => void;
  toggleMode: () => void;
} {
  const mode = useSyncExternalStore(
    subscribe,
    readStoredMode,
    () => "system" as ThemeMode,
  );
  const isDark = useSyncExternalStore(
    subscribe,
    () => resolveIsDark(readStoredMode()),
    () => false,
  );

  // mount / store 변경 시 `<html.dark>` 클래스를 isDark 와 동기화.
  // SSR safe: useEffect 는 client-only. THEME_INIT_SCRIPT 가 hydration 전에
  // 클래스를 이미 적용했어도, system 모드의 OS prefers 변화 / 다른 탭의
  // localStorage 변경은 이 effect 가 잡아 DOM 에 반영한다 (이슈 #745).
  useEffect(() => {
    applyHtmlClass(isDark);
  }, [isDark]);

  const setMode = useCallback((next: ThemeMode): void => {
    applyHtmlClass(resolveIsDark(next));
    persistMode(next);
    // 같은 탭에서는 'storage' 이벤트가 발생하지 않으므로 명시적으로 dispatch.
    if (typeof window !== "undefined") {
      window.dispatchEvent(
        new StorageEvent("storage", {
          key: THEME_STORAGE_KEY,
          newValue: next,
        }),
      );
    }
  }, []);

  const toggleMode = useCallback((): void => {
    // 3모드 순환: system → light → dark → system.
    const current = readStoredMode();
    const next: ThemeMode =
      current === "system" ? "light" : current === "light" ? "dark" : "system";
    setMode(next);
  }, [setMode]);

  return { mode, isDark, setMode, toggleMode };
}
