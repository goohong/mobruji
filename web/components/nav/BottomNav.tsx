/**
 * 모바일 하단 탭 navigation (closes #286).
 *
 * mobruji는 PWA로 모바일 우선이지만 그동안 페이지 간 이동은 홈의 SecondaryNav 또는
 * 브라우저 뒤로가기로만 가능했다. 측정/추천/히스토리/북마크 사이를 1탭에 오갈 수 있는
 * 글로벌 nav 가 필요해 layout.tsx에 글로벌로 끼워넣는다.
 *
 * 노출 정책:
 *  - 모바일 한정(`md:hidden`). 데스크탑은 본문 폭이 충분하고 홈의 SecondaryNav 가
 *    여전히 동작하므로 노출하지 않는다 (후속 PR에서 데스크탑 nav 별도 설계).
 *  - 모든 라우트에서 노출. 단, 추후 광고/풀스크린 측정 같은 화면이 생기면 해당 페이지가
 *    nav를 숨길 수 있도록 글로벌 노출만 책임진다.
 *
 * a11y:
 *  - `<nav role="navigation" aria-label="주요 메뉴">` 시맨틱.
 *  - active 탭에 `aria-current="page"` (정확 매치 또는 prefix 매치).
 *  - 각 탭은 최소 44px(`h-14`) 터치 타겟.
 *  - 아이콘은 SVG `aria-hidden`, 라벨은 텍스트로 별도 노출 (스크린리더 호환).
 *
 * 안전 영역:
 *  - iOS notch/home indicator 대응으로 `pb-[env(safe-area-inset-bottom)]`.
 *  - main 콘텐츠 가림 방지 padding은 layout.tsx에서 책임진다 (`pb-16 md:pb-0`).
 *
 * active 매치 규칙:
 *  - 홈("/")은 정확 매치만 (다른 모든 경로가 "/"로 시작하므로 prefix 매치 시 항상 active).
 *  - 그 외는 `pathname === href || pathname.startsWith(href + "/")` 로 자식 경로 포함.
 *    예: /voice-range/auto 에서 "측정" 탭 active 유지.
 *
 * 의존성:
 *  - lucide-react 같은 외부 아이콘 라이브러리는 package.json 보호영역 변경을 피하려고
 *    의도적으로 사용하지 않는다. SVG 인라인으로 5개만 그린다.
 */

"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";

type NavItem = {
  href: string;
  label: string;
  icon: ReactNode;
};

const NAV_ITEMS: ReadonlyArray<NavItem> = [
  { href: "/", label: "홈", icon: <HomeIcon /> },
  { href: "/voice-range/auto", label: "측정", icon: <MicIcon /> },
  { href: "/recommend", label: "추천", icon: <SparkleIcon /> },
  { href: "/history", label: "이력", icon: <ClockIcon /> },
  { href: "/bookmarks", label: "북마크", icon: <BookmarkIcon /> },
];

/**
 * 글로벌 모바일 하단 탭 navigation.
 *
 * SSR 안전 — usePathname은 next/navigation에서 클라이언트 컴포넌트로 안전하게 동작.
 * 측정 상태 등 사용자 컨텍스트와 무관하게 항상 같은 탭을 보여줘 페이지 간 이동만 책임진다.
 */
export function BottomNav() {
  const pathname = usePathname() ?? "/";

  /*
   * ADR-0018 단계 4 PR 5 — BottomNav 컴포넌트 토큰 swap.
   *
   * swap 한 element:
   *  1) <nav> 상단 border : border-zinc-200 dark:border-zinc-800 → --border
   *  2) active 탭 text : text-zinc-900 dark:text-zinc-50 → --text-primary
   *  3) inactive 탭 text : text-zinc-500 dark:text-zinc-400 → --text-tertiary
   *  4) inactive 탭 hover text : hover:text-zinc-700 dark:hover:text-zinc-200
   *     → hover:text-[var(--text-secondary)]
   *
   * 후속 PR (#1259 / 본 PR) 추가 swap:
   *  5) focus ring : focus-visible:ring-zinc-500 → --cta-secondary-ring
   *     (PR #1256 / #1259 동일 패턴 — 균일 outline 토큰)
   *
   * swap 완결 (PR #1659 — 잔존 zinc 마감):
   *  6) nav bg : bg-white/95 + dark:bg-zinc-950/95 → --surface-nav
   *  7) nav blur bg : supports-[backdrop-filter]:bg-white/80 + dark:…/80
   *     → supports-[backdrop-filter]:bg-[var(--surface-nav-blur)]
   *     opacity suffix 는 baked-alpha rgba 토큰으로 고정 (var() 호환 회피).
   *
   * 다크 모드: tokens.css `:where(html.dark)` selector 자동 swap → swap 한
   * element 의 `dark:` prefix 모두 제거. 미swap element 는 prefix 유지.
   */
  return (
    <nav
      aria-label="주요 메뉴"
      className="fixed inset-x-0 bottom-0 z-40 border-t border-[var(--border)] bg-[var(--surface-nav)] backdrop-blur supports-[backdrop-filter]:bg-[var(--surface-nav-blur)] md:hidden"
    >
      <ul
        className="mx-auto flex max-w-md items-stretch justify-around px-2 pt-1 pb-[env(safe-area-inset-bottom)]"
      >
        {NAV_ITEMS.map((item) => {
          const active = isActive(pathname, item.href);
          return (
            <li key={item.href} className="flex-1">
              <Link
                href={item.href}
                aria-current={active ? "page" : undefined}
                aria-label={item.label}
                className={[
                  "flex h-14 w-full flex-col items-center justify-center gap-0.5 rounded-lg text-xs font-medium transition-colors",
                  "focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)]",
                  active
                    ? "text-[var(--text-primary)]"
                    : "text-[var(--text-tertiary)] hover:text-[var(--text-secondary)]",
                ].join(" ")}
              >
                <span
                  aria-hidden="true"
                  className={active ? "scale-110 transition-transform" : "transition-transform"}
                >
                  {item.icon}
                </span>
                <span>{item.label}</span>
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}

/**
 * 현재 경로가 탭의 href와 매치하는지 판정한다.
 *
 * - 홈("/"): 정확 매치만. 다른 모든 경로가 "/"로 시작하므로 prefix 매치 시 항상 active가 되어버린다.
 * - 그 외: 정확 매치 또는 자식 경로 prefix 매치 (예: /voice-range/auto 에서 "측정" 활성).
 *
 * export 한 이유: 테스트에서 단위로 검증하기 위함.
 */
export function isActive(pathname: string, href: string): boolean {
  if (href === "/") {
    return pathname === "/";
  }
  return pathname === href || pathname.startsWith(href + "/");
}

/* --- Icons (lucide-react 의존성 회피, 24x24 stroke 기반) --- */

function HomeIcon() {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      width="22"
      height="22"
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

function MicIcon() {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      width="22"
      height="22"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <rect x="9" y="3" width="6" height="11" rx="3" />
      <path d="M5 11a7 7 0 0 0 14 0" />
      <line x1="12" y1="18" x2="12" y2="21" />
      <line x1="9" y1="21" x2="15" y2="21" />
    </svg>
  );
}

function SparkleIcon() {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      width="22"
      height="22"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M12 3l1.8 4.5L18 9l-4.2 1.5L12 15l-1.8-4.5L6 9l4.2-1.5L12 3z" />
      <path d="M19 14l.8 2L22 17l-2.2.8L19 20l-.8-2.2L16 17l2.2-1L19 14z" />
    </svg>
  );
}

function ClockIcon() {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      width="22"
      height="22"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <circle cx="12" cy="12" r="9" />
      <polyline points="12 7 12 12 15 14" />
    </svg>
  );
}

function BookmarkIcon() {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      width="22"
      height="22"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M6 3h12a1 1 0 0 1 1 1v17l-7-4-7 4V4a1 1 0 0 1 1-1z" />
    </svg>
  );
}
