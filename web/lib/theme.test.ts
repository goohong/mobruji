/**
 * useTheme / THEME_INIT_SCRIPT 단위 테스트 (이슈 #319).
 *
 * 검증 포인트:
 *   1) 초기 모드 = localStorage 우선 (없으면 "system").
 *   2) setMode("dark") → `<html.dark>` 적용 + localStorage 저장.
 *   3) toggleMode 순환: system → light → dark → system.
 *   4) system 모드일 때 prefers-color-scheme 변경 추종.
 *   5) THEME_INIT_SCRIPT 문자열에 키 + 클래스 이름이 정확히 포함.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";

import {
  THEME_DARK_CLASS,
  THEME_INIT_SCRIPT,
  THEME_STORAGE_KEY,
  useTheme,
} from "./theme";

function setOsPrefersDark(prefers: boolean): void {
  Object.defineProperty(window, "matchMedia", {
    writable: true,
    configurable: true,
    value: vi.fn().mockImplementation((query: string) => ({
      matches: query.includes("dark") ? prefers : false,
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  });
}

beforeEach(() => {
  window.localStorage.clear();
  document.documentElement.classList.remove(THEME_DARK_CLASS);
  setOsPrefersDark(false);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("useTheme", () => {
  it("초기 모드는 localStorage 가 비어 있으면 'system' 이고 OS prefers=light면 isDark=false", () => {
    const { result } = renderHook(() => useTheme());
    expect(result.current.mode).toBe("system");
    expect(result.current.isDark).toBe(false);
  });

  it("localStorage 에 'dark' 가 저장되어 있으면 mount 시 그 값을 사용한다", () => {
    window.localStorage.setItem(THEME_STORAGE_KEY, "dark");
    const { result } = renderHook(() => useTheme());
    expect(result.current.mode).toBe("dark");
    expect(result.current.isDark).toBe(true);
  });

  it("setMode('dark') 호출 시 <html.dark> 가 적용되고 localStorage 에 저장된다", () => {
    const { result } = renderHook(() => useTheme());
    act(() => {
      result.current.setMode("dark");
    });
    expect(document.documentElement.classList.contains(THEME_DARK_CLASS)).toBe(
      true,
    );
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe("dark");
    expect(result.current.mode).toBe("dark");
    expect(result.current.isDark).toBe(true);
  });

  it("setMode('light') 호출 시 다크 클래스가 제거되고 localStorage 에 light 저장", () => {
    document.documentElement.classList.add(THEME_DARK_CLASS);
    const { result } = renderHook(() => useTheme());
    act(() => {
      result.current.setMode("light");
    });
    expect(document.documentElement.classList.contains(THEME_DARK_CLASS)).toBe(
      false,
    );
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe("light");
    expect(result.current.isDark).toBe(false);
  });

  it("toggleMode 는 system → light → dark → system 순환한다", () => {
    const { result } = renderHook(() => useTheme());
    expect(result.current.mode).toBe("system");

    act(() => result.current.toggleMode());
    expect(result.current.mode).toBe("light");

    act(() => result.current.toggleMode());
    expect(result.current.mode).toBe("dark");

    act(() => result.current.toggleMode());
    expect(result.current.mode).toBe("system");
  });

  it("system 모드 + OS prefers=dark → isDark=true", () => {
    setOsPrefersDark(true);
    const { result } = renderHook(() => useTheme());
    expect(result.current.mode).toBe("system");
    expect(result.current.isDark).toBe(true);
  });

  it("system 모드 + OS prefers=light → isDark=false (resolveIsDark 분기 명시 가드)", () => {
    setOsPrefersDark(false);
    const { result } = renderHook(() => useTheme());
    expect(result.current.mode).toBe("system");
    expect(result.current.isDark).toBe(false);
  });

  it("system 모드 중 matchMedia 'change' 이벤트 발화 시 isDark 가 재계산된다", () => {
    // 'change' listener 를 capture 해 prefers=light→dark 전환을 시뮬레이션.
    let changeListener: ((event: MediaQueryListEvent) => void) | null = null;
    const mqlStub = {
      matches: false,
      media: "(prefers-color-scheme: dark)",
      onchange: null,
      addEventListener: vi.fn(
        (_evt: string, listener: (event: MediaQueryListEvent) => void) => {
          changeListener = listener;
        },
      ),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    };
    Object.defineProperty(window, "matchMedia", {
      writable: true,
      configurable: true,
      value: vi.fn(() => mqlStub),
    });

    const { result } = renderHook(() => useTheme());
    expect(result.current.isDark).toBe(false);

    act(() => {
      mqlStub.matches = true;
      changeListener?.({ matches: true } as MediaQueryListEvent);
    });
    expect(result.current.isDark).toBe(true);
  });
});

describe("THEME_INIT_SCRIPT", () => {
  it("localStorage 키와 다크 클래스 이름이 정확히 포함된다 — 모듈 상수와 sync 보장", () => {
    expect(THEME_INIT_SCRIPT).toContain(JSON.stringify(THEME_STORAGE_KEY));
    expect(THEME_INIT_SCRIPT).toContain(JSON.stringify(THEME_DARK_CLASS));
  });

  it("system 모드 + prefers-color-scheme 분기 + try/catch 안전 fallback 포함", () => {
    expect(THEME_INIT_SCRIPT).toContain("prefers-color-scheme: dark");
    expect(THEME_INIT_SCRIPT).toContain("try {");
    expect(THEME_INIT_SCRIPT).toContain("} catch");
  });
});
