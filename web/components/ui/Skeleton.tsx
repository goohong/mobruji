/**
 * 디자인 시스템 Skeleton 컴포넌트 (#1493).
 *
 * 데이터 로딩 중 콘텐츠의 윤곽을 미리 보여주는 플레이스홀더 블록. Tailwind 기본
 * `animate-pulse`(opacity 깜빡임) 대신 globals.css 의 `.animate-shimmer`(빛이
 * 좌→우로 쓸고 가는 sweep)를 입혀 토스/네이버 류 앱의 "쫀득한" 로딩 감각을 낸다.
 *
 * 형태는 `className` 으로 호출자가 지정한다(높이/폭/radius). 기본 radius 는
 * `--radius-md`. 의미 없는 시각 요소이므로 항상 `aria-hidden`.
 *
 * a11y: shimmer 애니메이션은 `prefers-reduced-motion: reduce` 에서 정적 base 톤만
 * 남는다(globals.css 가드). 로딩 상태의 의미 전달은 컨테이너의 `role="status"` +
 * `aria-label` 이 담당하고, 개별 Skeleton 블록은 스크린리더에서 숨긴다.
 */

import type { CSSProperties } from "react";

type SkeletonProps = {
  className?: string;
  /** 인라인 style passthrough — 동적 폭 등 토큰화 어려운 값 전용. */
  style?: CSSProperties;
};

export function Skeleton({ className = "", style }: SkeletonProps) {
  const merged = [
    "animate-shimmer rounded-[var(--radius-md)]",
    className,
  ]
    .filter((value) => value.length > 0)
    .join(" ");

  return <div aria-hidden="true" className={merged} style={style} />;
}
