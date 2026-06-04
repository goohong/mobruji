/**
 * 데스크톱 상단 헤더 navigation (closes #1717).
 *
 * 기존 데스크톱은 좌상단 floating 홈 아이콘 1개뿐이라 likes→bookmarks→songs 이동 시
 * 매번 홈을 경유해야 했다. 모바일 BottomNav 와 동일한 목적지·라벨(navItems.tsx 공통
 * source)을 상단 헤더로 영속 노출해 데스크톱 핵심 동선(페이지 간 이동)을 1클릭으로 만든다.
 *
 * 노출 정책:
 *  - 데스크톱 한정(`hidden md:block`). 모바일은 BottomNav(`md:hidden`)가 담당해 둘이
 *    상호 배타적으로 노출된다.
 *  - 좌측 브랜드 워드마크가 홈("/") 링크를 겸한다 — 중복 회피를 위해 nav 항목에서 홈은
 *    제외하고 측정/추천/이력/북마크만 링크로 노출한다. 홈에 있을 때는 브랜드에
 *    `aria-current="page"` 를 부여한다.
 *
 * a11y:
 *  - `<nav aria-label="주요 메뉴 (데스크톱)">` — BottomNav 와 라벨을 구분해 한 페이지에
 *    같은 이름의 landmark 가 두 개로 읽히는 혼동을 피한다 (둘은 뷰포트별로 한쪽만 노출).
 *  - active 링크에 `aria-current="page"`.
 *
 * 레이아웃:
 *  - 우상단 floating ThemeToggle(40px) 과 겹치지 않도록 nav 컨테이너에 `pr-16` 여백.
 *  - 고정 헤더 높이(h-14=56px) 만큼 본문이 가려지지 않도록 layout.tsx body 가 `md:pt-14`.
 */

"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

import { BrandWordmark } from "@/components/brand/BrandWordmark";

import { NAV_ITEMS, isActive } from "./navItems";

export function DesktopNav() {
  const pathname = usePathname() ?? "/";
  // 브랜드 워드마크가 홈 링크를 겸하므로 nav 항목에서 홈은 뺀다.
  const linkItems = NAV_ITEMS.filter((item) => item.href !== "/");
  const onHome = pathname === "/";

  return (
    <header className="fixed inset-x-0 top-0 z-30 hidden border-b border-[var(--border)] bg-[var(--surface-nav)] backdrop-blur supports-[backdrop-filter]:bg-[var(--surface-nav-blur)] md:block">
      <nav
        aria-label="주요 메뉴 (데스크톱)"
        className="mx-auto flex h-14 max-w-5xl items-center gap-1 px-4 pr-16"
      >
        <Link
          href="/"
          aria-label="홈"
          aria-current={onHome ? "page" : undefined}
          className="mr-3 flex items-center rounded-lg px-1 py-1 transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)]"
        >
          <BrandWordmark lang="ko" size="sm" theme="auto" />
        </Link>
        <ul className="flex items-center gap-1">
          {linkItems.map((item) => {
            const active = isActive(pathname, item.href);
            return (
              <li key={item.href}>
                <Link
                  href={item.href}
                  aria-current={active ? "page" : undefined}
                  className={[
                    "flex items-center gap-1.5 rounded-lg px-3 py-2 text-sm font-medium transition-colors",
                    "focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)]",
                    active
                      ? "bg-[var(--cta-secondary-bg)] text-[var(--text-primary)]"
                      : "text-[var(--text-tertiary)] hover:bg-[var(--cta-secondary-bg-hover)] hover:text-[var(--text-secondary)]",
                  ].join(" ")}
                >
                  <span aria-hidden="true" className="[&>svg]:h-[18px] [&>svg]:w-[18px]">
                    {item.icon}
                  </span>
                  <span>{item.label}</span>
                </Link>
              </li>
            );
          })}
        </ul>
      </nav>
    </header>
  );
}
