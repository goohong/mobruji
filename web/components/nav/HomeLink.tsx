"use client";

/**
 * 글로벌 홈(루트 /) 진입 floating 링크 (#1492).
 *
 * BottomNav 의 홈 탭은 모바일 한정(`md:hidden`)이라 데스크탑에서는 홈 외 페이지에서
 * 루트로 돌아갈 글로벌 진입점이 없었다. ThemeToggle(우상단 floating) 과 대칭으로
 * 좌상단에 floating 홈 링크를 layout 글로벌로 배치해 전 페이지 공통 진입점을 만든다.
 *
 * 노출 정책:
 *  - 홈("/") 에서는 이미 루트이므로 숨긴다 (중복 회피). usePathname 정확 매치 —
 *    BottomNav.isActive 의 홈 정확 매치 철학과 동일.
 *  - 그 외 모든 라우트에서 노출. ThemeToggle 처럼 모바일/데스크탑 공통 노출.
 *
 * a11y:
 *  - `aria-label="홈으로 이동"` 으로 스크린리더에 목적 명시.
 *  - 아이콘은 SVG `aria-hidden`. 최소 40px(`h-10 w-10`) 터치 타겟.
 *
 * 의존성:
 *  - lucide-react 등 외부 아이콘 라이브러리 회피 (fe 외부 lib 회피 패턴). SVG 인라인.
 */

import Link from "next/link";
import { usePathname } from "next/navigation";

export function HomeLink() {
  const pathname = usePathname() ?? "/";

  // 홈에서는 자기 자신으로 가는 링크가 중복이므로 숨긴다.
  if (pathname === "/") {
    return null;
  }

  return (
    <Link
      href="/"
      aria-label="홈으로 이동"
      title="홈"
      className={[
        // fixed top-left, safe-area 고려. ThemeToggle(우상단) 과 대칭. z-30 동일.
        "fixed top-3 left-3 z-30",
        "flex h-10 w-10 items-center justify-center rounded-full",
        "border border-[var(--border)] bg-[var(--surface-floating)] backdrop-blur",
        "text-[var(--text-secondary)] shadow-[var(--shadow-sm)] transition-colors duration-[var(--duration-base)]",
        "hover:bg-[var(--surface-floating-hover)] hover:text-[var(--text-primary)]",
        "focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)]",
        "pt-[env(safe-area-inset-top)]",
      ].join(" ")}
    >
      <span aria-hidden="true">
        <HomeIcon />
      </span>
    </Link>
  );
}

function HomeIcon() {
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
      <path d="M3 10.5L12 3l9 7.5" />
      <path d="M5 9.5V20a1 1 0 0 0 1 1h4v-6h4v6h4a1 1 0 0 0 1-1V9.5" />
    </svg>
  );
}
