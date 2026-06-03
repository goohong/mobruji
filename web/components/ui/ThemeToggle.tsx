"use client";

/**
 * 다크/라이트/시스템 모드 토글 (이슈 #319).
 *
 * - 모드 순환: system → light → dark → system. 각 상태에 맞는 아이콘과 aria-label.
 * - 글로벌 floating 버튼으로 layout 우상단에 배치 — BottomNav(5탭 포화) 침범 회피.
 * - SSR safe — 초기 렌더는 system 모드 + light 가정, mount 후 useTheme 가 보정.
 *   THEME_INIT_SCRIPT 가 hydration 전에 이미 `<html.dark>` 결정 → 시각적 flash 없음.
 * - a11y: `aria-label` 에 현재 모드와 다음 액션을 함께 명시. 키보드 focus ring 유지.
 *   `aria-pressed` 는 실제 다크 활성 여부(`isDark`) 와 동기화 — 사용자가 system 모드여도
 *   OS 가 dark 면 true. 스크린 리더가 "다크 활성 여부" 의 진실값을 듣게 한다 (이슈 #622).
 * - 의존성 0 (lucide-react 미사용, fe 25/33/34 외부 lib 회피 패턴).
 */

import { useTheme } from "@/lib/theme";

const NEXT_MODE_LABEL: Record<ReturnType<typeof useTheme>["mode"], string> = {
  system: "라이트 모드로 전환",
  light: "다크 모드로 전환",
  dark: "시스템 모드로 전환",
};

const CURRENT_MODE_LABEL: Record<ReturnType<typeof useTheme>["mode"], string> = {
  system: "시스템 모드",
  light: "라이트 모드",
  dark: "다크 모드",
};

export function ThemeToggle() {
  const { mode, isDark, toggleMode } = useTheme();

  /*
   * ADR-0018 단계 4 PR 5 — ThemeToggle 컴포넌트 토큰 swap.
   *
   * swap 한 element:
   *  1) border : border-zinc-200 dark:border-zinc-700 → --border
   *  2) text : text-zinc-700 dark:text-zinc-200 → --text-secondary
   *  3) hover text : hover:text-zinc-900 dark:hover:text-zinc-50 → --text-primary
   *  4) shadow : shadow-sm → --shadow-sm
   *  5) transition duration: transition-colors → duration-[var(--duration-base)]
   *
   * 후속 PR (#1259 / 본 PR) 추가 swap:
   *  6) focus ring : focus-visible:ring-zinc-500 → --cta-secondary-ring
   *     (PR #1256 / #1259 동일 패턴 — 균일 outline 토큰)
   *
   * swap 완결 (PR #1659 — 잔존 zinc 마감):
   *  7) floating bg : bg-white/90 + dark:bg-zinc-900/90 → --surface-floating
   *  8) floating hover bg : hover:bg-white + dark:hover:bg-zinc-900
   *     → hover:bg-[var(--surface-floating-hover)]
   *     opacity suffix 는 baked-alpha rgba 토큰으로 고정 (var() 호환 회피).
   *
   * 다크 모드: tokens.css `:where(html.dark)` selector 자동 swap → swap 한
   * element 의 `dark:` prefix 모두 제거. 미swap element 는 prefix 유지.
   */
  return (
    <button
      type="button"
      onClick={toggleMode}
      aria-label={`${CURRENT_MODE_LABEL[mode]} — ${NEXT_MODE_LABEL[mode]}`}
      aria-pressed={isDark}
      title={CURRENT_MODE_LABEL[mode]}
      data-theme-mode={mode}
      className={[
        // fixed top-right, safe-area 고려. BottomNav 와 z-index 겹치지 않도록 z-30.
        "fixed top-3 right-3 z-30",
        "flex h-10 w-10 items-center justify-center rounded-full",
        "border border-[var(--border)] bg-[var(--surface-floating)] backdrop-blur",
        "text-[var(--text-secondary)] shadow-[var(--shadow-sm)] transition-colors duration-[var(--duration-base)]",
        "hover:bg-[var(--surface-floating-hover)] hover:text-[var(--text-primary)]",
        "focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)]",
        "pt-[env(safe-area-inset-top)]",
      ].join(" ")}
    >
      <span aria-hidden="true">{ICONS[mode]}</span>
    </button>
  );
}

const ICONS: Record<ReturnType<typeof useTheme>["mode"], React.ReactNode> = {
  system: <SystemIcon />,
  light: <SunIcon />,
  dark: <MoonIcon />,
};

function SunIcon() {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v2" />
      <path d="M12 20v2" />
      <path d="m4.93 4.93 1.41 1.41" />
      <path d="m17.66 17.66 1.41 1.41" />
      <path d="M2 12h2" />
      <path d="M20 12h2" />
      <path d="m4.93 19.07 1.41-1.41" />
      <path d="m17.66 6.34 1.41-1.41" />
    </svg>
  );
}

function MoonIcon() {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
    </svg>
  );
}

function SystemIcon() {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <rect x="3" y="4" width="18" height="13" rx="2" />
      <path d="M8 21h8" />
      <path d="M12 17v4" />
    </svg>
  );
}
