/**
 * 모바일 하단 탭 navigation (closes #286).
 *
 * mobruji는 PWA로 모바일 우선이지만 그동안 페이지 간 이동은 홈의 SecondaryNav 또는
 * 브라우저 뒤로가기로만 가능했다. 측정/추천/히스토리/북마크 사이를 1탭에 오갈 수 있는
 * 글로벌 nav 가 필요해 layout.tsx에 글로벌로 끼워넣는다.
 *
 * 노출 정책:
 *  - 모바일 한정(`md:hidden`). 데스크탑은 상단 헤더(DesktopNav)가 동일 목적지를
 *    영속 노출한다 (closes #1717).
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
 *  - main 콘텐츠 가림 방지 padding은 layout.tsx에서 책임진다
 *    (`pb-[calc(5rem+env(safe-area-inset-bottom))] md:pb-0` — nav 높이 + safe-area 보정, #1718).
 *
 * nav 항목·아이콘·active 매치 규칙은 `navItems.tsx` 공통 모듈에 모여 있어 DesktopNav 와
 * 동일한 목적지·라벨을 공유한다 (IA 통일, closes #1717).
 */

"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

import { NAV_ITEMS, isActive } from "./navItems";

// 테스트(`BottomNav.test.tsx`)가 단위로 import 하던 active 매치 유틸 — 공통 모듈로
// 이관 후에도 기존 import 경로가 깨지지 않도록 re-export 한다.
export { isActive } from "./navItems";

/**
 * 글로벌 모바일 하단 탭 navigation.
 *
 * SSR 안전 — usePathname은 next/navigation에서 클라이언트 컴포넌트로 안전하게 동작.
 * 측정 상태 등 사용자 컨텍스트와 무관하게 항상 같은 탭을 보여줘 페이지 간 이동만 책임진다.
 */
export function BottomNav() {
  const pathname = usePathname() ?? "/";

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
