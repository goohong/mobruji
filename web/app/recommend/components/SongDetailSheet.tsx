/**
 * 곡 상세 Bottom Sheet 컴포넌트 (closes #1696, 구 SongDetailModal — closes #323).
 *
 * ## 도입 배경
 * `docs/features/ui-ux-redesign-pr-4-bottom-sheet.md` 단독 SoT. 기존 SongDetailModal
 * (`items-end` 하단 정렬만) 을 drag handle + slide-from-bottom + swipe-to-close 가 있는
 * 진짜 Bottom Sheet 으로 재설계한다. 토스/카카오뱅크/iOS HIG 의 한 손 조작 패턴 —
 * 모바일 노래방 도메인(한 손 조작 → 곡 상세 확인 → 즉시 dismiss)에 1:1 부합.
 *
 * ## 설계 결정 (spec §8 자율 default)
 * - **CSS-only + native touch event** (Q1=a): vaul/react-spring 미도입 → 번들 0 증가.
 * - **SongDetailSheet rename** (Q2=b): 의미 명확화. 호출 측 import 경로 전면 갱신.
 * - **25% 거리 임계 / 0.5px·ms velocity** (Q3=a, Q4=a): 가벼운 swipe 로도 close.
 * - **sheet 패턴 유지** (Q5=a): 데스크탑 modal 별도 분기 X — 단순성 + 일관성.
 * - **1 snap (full sheet)** (Q6=a): multi-snap 은 후속 PR.
 *
 * ## 회귀 보존 (구 SongDetailModal 동작 — spec §5-3 G1~G12)
 * - focus trap (G1) / ESC (G2) / backdrop click (G3) / scroll lock body+iOS (G4·G5) /
 *   swipe-to-close 거리·velocity·spring back·scroll 우선 (G6~G9) / role·aria (G10) /
 *   reduced-motion (G11) / 트리거 focus 복원 (G12).
 *
 * ## 접근성
 * - `role="dialog"` + `aria-modal="true"` + `aria-labelledby` + drag handle `role="separator"`.
 * - `prefers-reduced-motion: reduce` 시 enter/exit/drag 제스처 비활성 — 즉시 표시 + 버튼/ESC/backdrop 만.
 */

"use client";

import {
  useCallback,
  useEffect,
  useId,
  useRef,
  useState,
  type KeyboardEvent,
  type MouseEvent,
  type ReactNode,
} from "react";

import { useBottomSheet } from "@/hooks/useBottomSheet";

type SongDetailSheetProps = {
  open: boolean;
  onClose: () => void;
  /**
   * 시트 제목으로 노출되는 라벨. 보통 곡 제목을 그대로 받는다.
   * `aria-labelledby`로 연결되므로 빈 문자열은 권장하지 않는다.
   */
  titleLabel: string;
  /**
   * 시트 본문. 호출 측에서 자유로이 layout 한다.
   */
  children: ReactNode;
};

/**
 * 포커스 가능한 모든 요소 셀렉터. focus trap 순환 계산 + 초기 포커스 탐색에 사용.
 * - `tabindex="-1"` 명시적 비활성은 제외.
 * - `disabled` 속성은 셀렉터 필터로 거른다.
 */
const FOCUSABLE_SELECTOR = [
  "a[href]",
  "button:not([disabled])",
  "textarea:not([disabled])",
  "input:not([disabled])",
  "select:not([disabled])",
  "[tabindex]:not([tabindex='-1'])",
].join(", ");

// exit 애니메이션 길이 — globals.css `.animate-sheet-out`(250ms) 와 일치. 종료 후 unmount.
const EXIT_DURATION_MS = 250;

export function SongDetailSheet({
  open,
  onClose,
  titleLabel,
  children,
}: SongDetailSheetProps) {
  const dialogRef = useRef<HTMLDivElement | null>(null);
  const previouslyFocusedRef = useRef<HTMLElement | null>(null);
  const titleId = useId();

  const {
    sheetRef,
    translateY,
    isDragging,
    prefersReducedMotion,
    onTouchStart,
    onTouchMove,
    onTouchEnd,
  } = useBottomSheet({ isOpen: open, onClose });

  // open=false 전환 시 exit 애니메이션 동안 시트를 DOM 에 유지하기 위한 내부 mount state.
  // open 변화 감지는 effect 가 아니라 "이전 렌더 값 비교 + 렌더 중 setState" 패턴으로
  // 처리한다 (React 권장 — cascading effect 회피, set-state-in-effect 룰 준수).
  const [isRendered, setIsRendered] = useState(open);
  const [isLeaving, setIsLeaving] = useState(false);
  const [prevOpen, setPrevOpen] = useState(open);

  if (open !== prevOpen) {
    setPrevOpen(open);
    if (open) {
      setIsRendered(true);
      setIsLeaving(false);
    } else if (isRendered && !prefersReducedMotion) {
      // 모션 허용 — exit 슬라이드 동안 mount 유지.
      setIsLeaving(true);
    } else {
      // 모션 비활성 또는 이미 unmount — 즉시 unmount.
      setIsRendered(false);
    }
  }

  // exit 애니메이션 종료 후 unmount. setState 는 timer 콜백(비동기) 안 — 동기 effect 본문 아님.
  useEffect(() => {
    if (!isLeaving) {
      return;
    }
    const timeoutId = window.setTimeout(() => {
      setIsRendered(false);
      setIsLeaving(false);
    }, EXIT_DURATION_MS);
    return () => window.clearTimeout(timeoutId);
  }, [isLeaving]);

  /**
   * 오픈 시 직전 포커스 저장 + 시트 내부 첫 포커스 가능 요소로 포커스 이동.
   * 닫힘(open=false) 시 cleanup 이 직전 포커스(트리거 SongCard)를 복원 (G1·G12).
   */
  useEffect(() => {
    if (!open) {
      return;
    }
    previouslyFocusedRef.current =
      (document.activeElement as HTMLElement | null) ?? null;

    const timeoutId = window.setTimeout(() => {
      const dialog = dialogRef.current;
      if (!dialog) {
        return;
      }
      const focusables = dialog.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR);
      const first = focusables[0];
      if (first) {
        first.focus();
      } else {
        dialog.focus();
      }
    }, 0);

    return () => {
      window.clearTimeout(timeoutId);
      const previous = previouslyFocusedRef.current;
      if (previous && typeof previous.focus === "function") {
        previous.focus();
      }
    };
  }, [open]);

  /**
   * ESC 닫기 (G2) + Tab/Shift+Tab 순환 (G1). div 기반 dialog 호환을 위해 명시적 처리.
   */
  const handleKeyDown = useCallback(
    (event: KeyboardEvent<HTMLDivElement>) => {
      if (event.key === "Escape") {
        event.stopPropagation();
        onClose();
        return;
      }
      if (event.key !== "Tab") {
        return;
      }
      const dialog = dialogRef.current;
      if (!dialog) {
        return;
      }
      const focusables = Array.from(
        dialog.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR),
      ).filter((el) => !el.hasAttribute("aria-hidden"));
      if (focusables.length === 0) {
        return;
      }
      const first = focusables[0];
      const last = focusables[focusables.length - 1];
      const active = document.activeElement as HTMLElement | null;

      if (event.shiftKey) {
        if (active === first || !dialog.contains(active)) {
          event.preventDefault();
          last.focus();
        }
      } else {
        if (active === last || !dialog.contains(active)) {
          event.preventDefault();
          first.focus();
        }
      }
    },
    [onClose],
  );

  /**
   * backdrop 클릭 닫기 (G3). 본문 영역 클릭은 currentTarget !== target 이라 무시.
   */
  const handleBackdropClick = useCallback(
    (event: MouseEvent<HTMLDivElement>) => {
      if (event.target === event.currentTarget) {
        onClose();
      }
    },
    [onClose],
  );

  /**
   * scroll lock (G4·G5) — iOS Safari rubber-band 회피.
   * `overflow: hidden` + `position: fixed; top: -{scrollY}px` 로 배경 고정,
   * 닫힘 시 scroll 위치 복원. open 기준으로 lock/unlock.
   */
  useEffect(() => {
    if (!open) {
      return;
    }
    const scrollY = window.scrollY;
    const body = document.body;
    const previous = {
      overflow: body.style.overflow,
      position: body.style.position,
      top: body.style.top,
      width: body.style.width,
    };
    body.style.overflow = "hidden";
    body.style.position = "fixed";
    body.style.top = `-${scrollY}px`;
    body.style.width = "100%";
    return () => {
      body.style.overflow = previous.overflow;
      body.style.position = previous.position;
      body.style.top = previous.top;
      body.style.width = previous.width;
      window.scrollTo(0, scrollY);
    };
  }, [open]);

  if (!isRendered) {
    return null;
  }

  const sheetTransform = translateY > 0 ? `translateY(${translateY}px)` : undefined;
  // 드래그 중에는 transition 0 (손가락 추적), 놓으면 spring back transition.
  const sheetTransition = isDragging
    ? "none"
    : "transform 200ms var(--ease-emphasized)";
  // enter/exit 애니메이션은 드래그/translateY 가 0 일 때만 — 드래그 inline transform 과 충돌 방지.
  const sheetAnimationClass =
    translateY > 0
      ? ""
      : isLeaving
        ? "animate-sheet-out"
        : "animate-sheet-in";

  return (
    <div
      role="presentation"
      onClick={handleBackdropClick}
      className={`fixed inset-0 z-50 flex items-end justify-center bg-[var(--modal-backdrop)] backdrop-blur-sm ${
        isLeaving ? "animate-fade-out" : "animate-fade-in"
      }`}
    >
      <div
        ref={(node) => {
          dialogRef.current = node;
          sheetRef.current = node;
        }}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        onKeyDown={handleKeyDown}
        onTouchStart={onTouchStart}
        onTouchMove={onTouchMove}
        onTouchEnd={onTouchEnd}
        style={{ transform: sheetTransform, transition: sheetTransition }}
        className={`relative flex max-h-[90dvh] w-full max-w-lg flex-col gap-4 overflow-y-auto overscroll-contain rounded-t-2xl bg-[var(--surface-modal)] px-6 pb-6 pt-3 shadow-2xl ring-1 ring-[var(--ring-soft-detail)] sm:mb-6 sm:rounded-2xl ${sheetAnimationClass}`}
      >
        <div
          role="separator"
          aria-orientation="horizontal"
          aria-label="아래로 끌어 닫기"
          className="mx-auto h-1 w-9 shrink-0 rounded-full bg-[var(--text-tertiary)]"
        />
        <div className="flex items-start justify-between gap-4">
          <h2
            id={titleId}
            className="text-xl font-semibold text-[var(--text-primary)]"
          >
            {titleLabel}
          </h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="상세 닫기"
            className="inline-flex h-11 w-11 shrink-0 items-center justify-center rounded-full text-[var(--text-caption)] transition-colors hover:bg-[var(--cta-secondary-bg-hover)] hover:text-[var(--text-primary)] focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)]"
          >
            <span aria-hidden="true" className="text-lg leading-none">
              ×
            </span>
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}
