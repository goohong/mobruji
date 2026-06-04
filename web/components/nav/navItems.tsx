/**
 * 글로벌 navigation 공통 source of truth (closes #1717).
 *
 * 모바일 하단 탭(BottomNav)과 데스크톱 상단 헤더(DesktopNav)가 동일한 목적지·라벨을
 * 공유하도록 nav 항목·아이콘·active 매치 규칙을 이 한 곳에 모은다. 두 컴포넌트가
 * 각자 배열을 들고 있으면 라벨/집합이 어긋날 위험이 있어 IA 통일을 위해 추출했다.
 *
 * 의존성:
 *  - lucide-react 등 외부 아이콘 라이브러리는 package.json 보호영역 변경을 피하려고
 *    의도적으로 사용하지 않는다. SVG 인라인으로만 그린다.
 */

import type { ReactNode } from "react";

export type NavItem = {
  href: string;
  label: string;
  icon: ReactNode;
};

export const NAV_ITEMS: ReadonlyArray<NavItem> = [
  { href: "/", label: "홈", icon: <HomeIcon /> },
  { href: "/voice-range/auto", label: "측정", icon: <MicIcon /> },
  { href: "/recommend", label: "추천", icon: <SparkleIcon /> },
  { href: "/history", label: "이력", icon: <ClockIcon /> },
  { href: "/bookmarks", label: "북마크", icon: <BookmarkIcon /> },
];

/**
 * 현재 경로가 탭의 href와 매치하는지 판정한다.
 *
 * - 홈("/"): 정확 매치만. 다른 모든 경로가 "/"로 시작하므로 prefix 매치 시 항상 active가 되어버린다.
 * - 그 외: 정확 매치 또는 자식 경로 prefix 매치 (예: /voice-range/auto 에서 "측정" 활성).
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
