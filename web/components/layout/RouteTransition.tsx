/**
 * 라우트 전환 시 본문에 절제된 fade-in 을 입히는 래퍼 (#1493).
 *
 * App Router 는 layout 을 유지한 채 page subtree 만 교체하므로, layout 안에서
 * `children` 을 감싸는 div 의 `key` 를 현재 pathname 으로 두면 페이지 이동마다
 * 래퍼가 remount 되어 `animate-fade-in` keyframe 이 재실행된다. 토스/네이버
 * 류 앱의 "화면이 부드럽게 들어오는" 전환 감각을 가장 가벼운 비용으로 재현한다.
 *
 * 레이아웃 보존: 기존 페이지(`<main className="flex flex-1 flex-col ...">`)는
 * body(`flex flex-col`)의 직속 flex child 로서 `flex-1` 로 늘어났다. 래퍼가
 * 중간에 끼므로 동일한 `flex flex-1 flex-col` 을 입혀 grow 체인을 유지한다.
 *
 * a11y: fade 는 `animate-fade-in` 유틸(globals.css)에 위임하며, 해당 유틸은
 * `prefers-reduced-motion: reduce` 에서 keyframe 을 끈다 — motion-sensitive
 * 사용자에게는 즉시 표시.
 */

"use client";

import { usePathname } from "next/navigation";
import type { ReactNode } from "react";

type RouteTransitionProps = {
  children: ReactNode;
};

export function RouteTransition({ children }: RouteTransitionProps) {
  const pathname = usePathname() ?? "/";
  return (
    <div key={pathname} className="animate-fade-in flex flex-1 flex-col">
      {children}
    </div>
  );
}
