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
 * 같은 탭에서 모드 변경을 알리는 보조 custom event 이름 (이슈 #1170 production fix).
 *
 * 배경:
 *  - `dispatchEvent(new StorageEvent("storage", {key, newValue}))` 는 spec 상 같은
 *    탭 listener 에게 fire 되지만, 일부 production browser 에서 `StorageEvent.key`
 *    필드가 constructor option 에서 누락된 채 `null` 로 떨어지는 보고가 있음.
 *  - 그 경우 `subscribe()` 안의 `event.key === THEME_STORAGE_KEY` 필터가 false 가
 *    되어 useSyncExternalStore 의 callback 이 호출되지 않음 → React state 가
 *    stale 한 채 button click 이 시각적으로만 반영되거나 그마저도 깜빡 후 revert.
 *  - 이 custom event 는 useSyncExternalStore re-read 를 강제하는 보조 채널.
 *  - StorageEvent 가 정상이면 callback 이 2번 호출되지만 useSyncExternalStore 가
 *    snapshot 동일성 비교로 idempotent — 추가 re-render 없음.
 */
export const THEME_CHANGE_EVENT = "mobruji-theme-change";

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
  // 다른 탭의 localStorage 변경 — spec 상 `StorageEvent.key` 가 채워진다.
  const onStorage = (event: StorageEvent): void => {
    if (event.key === THEME_STORAGE_KEY) {
      callback();
    }
  };
  // 같은 탭 setMode 알림 — `THEME_CHANGE_EVENT` custom event (이슈 #1170 fix).
  // 인공 StorageEvent constructor 의 key 필드 누락 회귀 우회.
  const onThemeChange = (): void => callback();
  const mql = window.matchMedia("(prefers-color-scheme: dark)");
  const onPrefersChange = (): void => callback();
  window.addEventListener("storage", onStorage);
  window.addEventListener(THEME_CHANGE_EVENT, onThemeChange);
  mql.addEventListener("change", onPrefersChange);
  return () => {
    window.removeEventListener("storage", onStorage);
    window.removeEventListener(THEME_CHANGE_EVENT, onThemeChange);
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
    // 같은 탭에서는 native 'storage' 이벤트가 발생하지 않는다.
    // 인공 StorageEvent constructor 는 일부 production browser 에서 `key` 필드가
    // 누락된 채 fire 되어 subscribe filter 가 false → React state stale 상태로
    // 시각 토글만 됐다가 다음 effect 에서 revert 되는 회귀가 있었다 (이슈 #1170).
    // 같은 탭 broadcast 는 의미가 명확한 custom event 채널로 일원화.
    if (typeof window !== "undefined") {
      window.dispatchEvent(new Event(THEME_CHANGE_EVENT));
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
