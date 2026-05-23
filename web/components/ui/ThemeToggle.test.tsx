/**
 * ThemeToggle 단위 테스트 (이슈 #319).
 *
 * - 초기 모드 시스템 아이콘 + aria-label.
 * - 클릭 시 모드 순환 + `<html.dark>` 토글.
 * - a11y: 단일 button, role=button, focus 가능.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { ThemeToggle } from "./ThemeToggle";
import { THEME_DARK_CLASS, THEME_STORAGE_KEY } from "@/lib/theme";

/** matchMedia mock — change listener 캡처해 OS prefers 변화를 수동 fire 가능. */
function setOsPrefersDark(prefers: boolean): (next: boolean) => void {
  const listeners = new Set<(ev: MediaQueryListEvent) => void>();
  let current = prefers;
  Object.defineProperty(window, "matchMedia", {
    writable: true,
    configurable: true,
    value: vi.fn().mockImplementation((query: string) => ({
      get matches() {
        return query.includes("dark") ? current : false;
      },
      media: query,
      onchange: null,
      addEventListener: (_: string, cb: (ev: MediaQueryListEvent) => void) => listeners.add(cb),
      removeEventListener: (_: string, cb: (ev: MediaQueryListEvent) => void) => listeners.delete(cb),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  });
  return (next: boolean): void => {
    current = next;
    listeners.forEach((cb) => cb({ matches: next } as MediaQueryListEvent));
  };
}

beforeEach(() => {
  window.localStorage.clear();
  document.documentElement.classList.remove(THEME_DARK_CLASS);
  setOsPrefersDark(false);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("ThemeToggle", () => {
  it("초기 렌더 시 system 모드 라벨이 노출된다", () => {
    render(<ThemeToggle />);
    const button = screen.getByRole("button");
    expect(button.getAttribute("aria-label")).toContain("시스템 모드");
    expect(button.getAttribute("data-theme-mode")).toBe("system");
  });

  it("클릭 시 모드 순환 + aria-label 갱신 + <html.dark> 토글", () => {
    render(<ThemeToggle />);
    const button = screen.getByRole("button");

    // system → light
    fireEvent.click(button);
    expect(button.getAttribute("data-theme-mode")).toBe("light");
    expect(button.getAttribute("aria-label")).toContain("라이트 모드");
    expect(document.documentElement.classList.contains(THEME_DARK_CLASS)).toBe(
      false,
    );

    // light → dark — <html.dark> 적용 확인.
    fireEvent.click(button);
    expect(button.getAttribute("data-theme-mode")).toBe("dark");
    expect(button.getAttribute("aria-label")).toContain("다크 모드");
    expect(document.documentElement.classList.contains(THEME_DARK_CLASS)).toBe(
      true,
    );

    // dark → system (OS prefers=light → 클래스 제거).
    fireEvent.click(button);
    expect(button.getAttribute("data-theme-mode")).toBe("system");
    expect(document.documentElement.classList.contains(THEME_DARK_CLASS)).toBe(
      false,
    );
  });

  it("localStorage 에 마지막 선택이 저장된다 — 다음 방문 보존", () => {
    render(<ThemeToggle />);
    const button = screen.getByRole("button");
    fireEvent.click(button); // light
    fireEvent.click(button); // dark
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe("dark");
  });

  it("초기 로드 시 localStorage 저장값을 복원한다", () => {
    window.localStorage.setItem(THEME_STORAGE_KEY, "dark");
    render(<ThemeToggle />);
    const button = screen.getByRole("button");
    expect(button.getAttribute("data-theme-mode")).toBe("dark");
    expect(document.documentElement.classList.contains(THEME_DARK_CLASS)).toBe(
      true,
    );
  });

  it("system 모드 순환도 localStorage 에 저장된다", () => {
    render(<ThemeToggle />);
    const button = screen.getByRole("button");
    fireEvent.click(button); // light
    fireEvent.click(button); // dark
    fireEvent.click(button); // system
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe("system");
  });

  it("localStorage 비정상 값은 system 으로 fallback 된다", () => {
    window.localStorage.setItem(THEME_STORAGE_KEY, "invalid-mode");
    render(<ThemeToggle />);
    const button = screen.getByRole("button");
    expect(button.getAttribute("data-theme-mode")).toBe("system");
  });

  it("aria-pressed 가 dark 모드일 때만 true 로 동기화된다", () => {
    render(<ThemeToggle />);
    const button = screen.getByRole("button");
    expect(button.getAttribute("aria-pressed")).toBe("false"); // system
    fireEvent.click(button); // light
    expect(button.getAttribute("aria-pressed")).toBe("false");
    fireEvent.click(button); // dark
    expect(button.getAttribute("aria-pressed")).toBe("true");
    fireEvent.click(button); // system
    expect(button.getAttribute("aria-pressed")).toBe("false");
  });

  it("Enter 키로 모드 순환이 활성화된다", async () => {
    const user = userEvent.setup();
    render(<ThemeToggle />);
    const button = screen.getByRole("button");
    button.focus();
    await user.keyboard("{Enter}");
    expect(button.getAttribute("data-theme-mode")).toBe("light");
  });

  it("Space 키로 모드 순환이 활성화된다", async () => {
    const user = userEvent.setup();
    render(<ThemeToggle />);
    const button = screen.getByRole("button");
    button.focus();
    await user.keyboard(" ");
    expect(button.getAttribute("data-theme-mode")).toBe("light");
  });

  it("system 모드 + OS dark 면 <html.dark> 가 적용된다", () => {
    setOsPrefersDark(true);
    render(<ThemeToggle />);
    expect(document.documentElement.classList.contains(THEME_DARK_CLASS)).toBe(true);
  });

  it("system 모드 중 OS dark→light 변경이 즉시 반영된다", () => {
    const fireOsChange = setOsPrefersDark(true);
    render(<ThemeToggle />);
    expect(document.documentElement.classList.contains(THEME_DARK_CLASS)).toBe(true);
    // matchMedia listener 외부에서 trigger → React effect flush 강제 (#745).
    act(() => fireOsChange(false));
    expect(document.documentElement.classList.contains(THEME_DARK_CLASS)).toBe(false);
  });

  it("explicit dark 모드면 OS prefers 변경을 무시한다", () => {
    window.localStorage.setItem(THEME_STORAGE_KEY, "dark");
    const fireOsChange = setOsPrefersDark(true);
    render(<ThemeToggle />);
    expect(document.documentElement.classList.contains(THEME_DARK_CLASS)).toBe(true);
    fireOsChange(false);
    expect(document.documentElement.classList.contains(THEME_DARK_CLASS)).toBe(true);
  });

  it("다른 탭이 mobruji-theme 를 dark 로 변경하면 <html.dark> 가 동기화된다", () => {
    render(<ThemeToggle />);
    expect(document.documentElement.classList.contains(THEME_DARK_CLASS)).toBe(false);
    // 다른 탭 시뮬레이션 — setMode 우회, 순수 storage 이벤트 + 사전 localStorage 반영.
    // dispatchEvent 는 React lifecycle 밖에서 트리거되므로 act 로 effect flush (#745).
    act(() => {
      window.localStorage.setItem(THEME_STORAGE_KEY, "dark");
      window.dispatchEvent(
        new StorageEvent("storage", { key: THEME_STORAGE_KEY, newValue: "dark" }),
      );
    });
    expect(document.documentElement.classList.contains(THEME_DARK_CLASS)).toBe(true);
  });

  it("다른 key 의 storage 이벤트는 무시된다 — <html.dark> 불변", () => {
    window.localStorage.setItem(THEME_STORAGE_KEY, "dark");
    render(<ThemeToggle />);
    expect(document.documentElement.classList.contains(THEME_DARK_CLASS)).toBe(true);
    // 무관 key — subscribe filter 가 callback 호출을 막아야 함.
    window.localStorage.setItem(THEME_STORAGE_KEY, "light"); // 저장은 바뀌나 이벤트 key 가 다름
    window.dispatchEvent(
      new StorageEvent("storage", { key: "other-key", newValue: "noise" }),
    );
    // 이벤트가 무시되었으므로 useSyncExternalStore re-read 없음 → 클래스 그대로.
    expect(document.documentElement.classList.contains(THEME_DARK_CLASS)).toBe(true);
  });
});
