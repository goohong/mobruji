/**
 * 좋아요 토글용 heart pop + sparkle 토글 버튼 (closes #1685, UI/UX redesign PR6).
 *
 * iOS Health 좋아요 / 카카오뱅크 "즉시 ✅" 류의 **눌렀을 때 바로 반응하는** 감각을
 * 노린다. CSS-only(@keyframes) 로만 구현 — framer-motion / react-spring 도입 X.
 *
 * 모션 3종 (탭 1회당):
 *   1. Heart pop — 하트 글리프가 scale 1 → 1.3 → 1 (`animate-heart-pop`, 400ms
 *      `var(--ease-emphasized)`). 매 탭마다 `tapCount` 가 증가하며 key 가 바뀌어
 *      keyframe 이 재시작한다(초기 렌더 tapCount=0 은 무애니).
 *   2. Sparkle — 좋아요로 **켜질 때만**(현재 비활성) 3개 입자가 위로 흩어지며
 *      fade-out(`heart-sparkle`, 600ms). 끌 때는 축포를 띄우지 않아 절제한다.
 *   3. Haptic — 지원 환경에서만 `navigator.vibrate(10)`. 미지원/iOS 정책 거부 시
 *      optional chain 으로 graceful (에러 throw 0).
 *
 * a11y: `prefers-reduced-motion` 사용자에게는 globals.css 가드가 pop/sparkle
 * keyframe 자체를 끈다(시각적 "없음"). 상태 표현은 모션이 아닌 색/텍스트/aria 로도
 * 전달되므로 모션을 꺼도 정보 손실이 없다.
 *
 * 카드 컨텍스트: `/recommend` 등에서 카드 전체가 Link/모달 trigger 로 감싸이므로,
 * 좋아요 클릭이 페이지 이동·모달을 트리거하지 않도록 handleClick 이 이벤트를 격리한다.
 */

"use client";

import { useState, type CSSProperties, type MouseEvent } from "react";

/**
 * Sparkle 3입자의 도착 지점(translate offset). 위쪽으로 부채꼴 흩어지도록 좌·우·정상단
 * 3방향. `--sparkle-x/y` 는 `heart-sparkle` keyframe 이 읽는다(globals.css).
 */
const SPARKLE_OFFSETS: ReadonlyArray<Record<string, string>> = [
  { "--sparkle-x": "-13px", "--sparkle-y": "-11px" },
  { "--sparkle-x": "13px", "--sparkle-y": "-9px" },
  { "--sparkle-x": "0px", "--sparkle-y": "-17px" },
];

type HeartPopProps = {
  /** 현재 좋아요 상태. 하트 글리프/색/aria-pressed 를 결정한다. */
  active: boolean;
  /** 탭 시 호출 — 실제 토글(낙관 + BE)은 호출 측 mutation 훅이 담당. */
  onToggle: () => void;
  /** 시각 라벨 ("좋아요" / "좋아요 취소"). */
  label: string;
  /** 스크린리더용 전체 라벨 ("<곡> 좋아요" 등). */
  ariaLabel: string;
  /** mutation 진행 중 — 중복 클릭 방지 disabled. */
  disabled?: boolean;
  /** mutation 진행 중 — aria-busy 노출. */
  busy?: boolean;
};

export function HeartPop({
  active,
  onToggle,
  label,
  ariaLabel,
  disabled = false,
  busy = false,
}: HeartPopProps) {
  // 매 탭마다 증가 → key 변경으로 pop keyframe 재시작. 0(초기)은 무애니.
  const [tapCount, setTapCount] = useState(0);
  // 좋아요로 켜질 때만 증가 → sparkle 축포 재생. 0(초기)은 입자 미렌더.
  const [sparkleBurst, setSparkleBurst] = useState(0);

  function handleClick(event: MouseEvent<HTMLButtonElement>) {
    // 카드 Link/모달 trigger 로 이벤트가 새 나가지 않도록 격리.
    event.preventDefault();
    event.stopPropagation();
    // 햅틱 — 미지원/iOS 정책 거부 시 graceful (optional chain).
    if (typeof navigator !== "undefined") {
      navigator.vibrate?.(10);
    }
    if (!active) {
      setSparkleBurst((count) => count + 1);
    }
    setTapCount((count) => count + 1);
    onToggle();
  }

  return (
    <button
      type="button"
      onClick={handleClick}
      disabled={disabled}
      aria-pressed={active}
      aria-busy={busy}
      aria-label={ariaLabel}
      data-active={active}
      className={`inline-flex min-h-11 items-center gap-1.5 self-start rounded-full px-3.5 py-2 text-sm font-medium transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)] disabled:cursor-progress disabled:opacity-60 ${
        active
          ? "bg-rose-100 text-rose-700 hover:bg-rose-200 dark:bg-rose-950 dark:text-rose-300 dark:hover:bg-rose-900"
          : "text-[var(--text-secondary)] hover:bg-[var(--cta-secondary-bg-hover)]"
      }`}
    >
      <span
        aria-hidden="true"
        className="relative inline-flex items-center justify-center"
      >
        <span
          key={tapCount}
          className={tapCount > 0 ? "inline-block animate-heart-pop" : "inline-block"}
        >
          {active ? "❤️" : "🤍"}
        </span>
        {sparkleBurst > 0 ? (
          <span
            key={sparkleBurst}
            aria-hidden="true"
            className="pointer-events-none absolute inset-0"
            data-testid="heart-sparkle-group"
          >
            {SPARKLE_OFFSETS.map((offset, index) => (
              <span
                key={index}
                className="heart-sparkle"
                style={offset as CSSProperties}
              />
            ))}
          </span>
        ) : null}
      </span>
      <span>{label}</span>
    </button>
  );
}
