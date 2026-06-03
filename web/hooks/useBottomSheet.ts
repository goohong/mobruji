/**
 * Bottom Sheet drag/swipe-to-close 제스처 훅 (closes #1696).
 *
 * `docs/features/ui-ux-redesign-pr-4-bottom-sheet.md` §5-2 단독 SoT. CSS-only +
 * native touch event 자체 구현 (Q1 자율 default = (a) — vaul/react-spring 미도입,
 * 번들 0 증가). 시트를 손가락으로 아래로 끌면 translateY 가 따라오고, 놓는 순간
 * (1) 드래그 거리가 시트 높이의 `closeThresholdPercent`% 를 넘거나 (2) 마지막
 * velocity 가 `closeVelocityThreshold`(px/ms) 를 넘으면 close, 둘 다 미달이면
 * translateY 0 으로 spring back.
 *
 * iOS Safari rubber-band 회피: 시트 내부 스크롤 위치(scrollTop)가 0 이 아니면
 * 드래그를 시작하지 않는다 (스크롤 우선). `prefers-reduced-motion: reduce` 시
 * 드래그 제스처 전체 비활성 — 닫기 버튼 / ESC / backdrop 만 남긴다.
 */

import {
  useCallback,
  useRef,
  useState,
  type RefObject,
  type TouchEvent as ReactTouchEvent,
} from "react";

type UseBottomSheetOptions = {
  isOpen: boolean;
  onClose: () => void;
  /** 드래그 거리가 시트 높이의 이 % 를 넘으면 close. default 25. */
  closeThresholdPercent?: number;
  /** 마지막 touchmove velocity(px/ms)가 이 값을 넘으면 거리 미달이어도 close. default 0.5. */
  closeVelocityThreshold?: number;
};

type UseBottomSheetResult = {
  sheetRef: RefObject<HTMLDivElement | null>;
  translateY: number;
  isDragging: boolean;
  prefersReducedMotion: boolean;
  onTouchStart: (event: ReactTouchEvent<HTMLDivElement>) => void;
  onTouchMove: (event: ReactTouchEvent<HTMLDivElement>) => void;
  onTouchEnd: () => void;
};

function readReducedMotion(): boolean {
  if (typeof window === "undefined" || typeof window.matchMedia !== "function") {
    return false;
  }
  return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

export function useBottomSheet({
  isOpen,
  onClose,
  closeThresholdPercent = 25,
  closeVelocityThreshold = 0.5,
}: UseBottomSheetOptions): UseBottomSheetResult {
  const sheetRef = useRef<HTMLDivElement | null>(null);
  const [translateY, setTranslateY] = useState(0);
  const [isDragging, setIsDragging] = useState(false);
  // 마운트 시 1회 평가 (lazy initializer) — effect 안 setState 회피.
  const [prefersReducedMotion] = useState(readReducedMotion);

  // 드래그 추적값 — 렌더 유발 없이 mutable ref 로 보관.
  const startYRef = useRef(0);
  const lastYRef = useRef(0);
  const lastTimeRef = useRef(0);
  const velocityRef = useRef(0);
  const activeRef = useRef(false);

  const onTouchStart = useCallback(
    (event: ReactTouchEvent<HTMLDivElement>) => {
      if (!isOpen || prefersReducedMotion) {
        return;
      }
      // 시트 내부 스크롤 위치가 최상단(0)이 아니면 스크롤 우선 — swipe 비활성.
      const sheet = sheetRef.current;
      if (sheet && sheet.scrollTop > 0) {
        return;
      }
      const touch = event.touches[0];
      if (!touch) {
        return;
      }
      activeRef.current = true;
      startYRef.current = touch.clientY;
      lastYRef.current = touch.clientY;
      lastTimeRef.current = event.timeStamp;
      velocityRef.current = 0;
      setIsDragging(true);
    },
    [isOpen, prefersReducedMotion],
  );

  const onTouchMove = useCallback((event: ReactTouchEvent<HTMLDivElement>) => {
    if (!activeRef.current) {
      return;
    }
    const touch = event.touches[0];
    if (!touch) {
      return;
    }
    const deltaY = touch.clientY - startYRef.current;
    // 위로 끄는(음수) 동작은 무시 — 시트는 아래로만 끌린다.
    const nextTranslateY = deltaY > 0 ? deltaY : 0;

    const elapsed = event.timeStamp - lastTimeRef.current;
    if (elapsed > 0) {
      velocityRef.current = (touch.clientY - lastYRef.current) / elapsed;
    }
    lastYRef.current = touch.clientY;
    lastTimeRef.current = event.timeStamp;

    setTranslateY(nextTranslateY);
  }, []);

  const onTouchEnd = useCallback(() => {
    if (!activeRef.current) {
      return;
    }
    activeRef.current = false;
    setIsDragging(false);

    const sheet = sheetRef.current;
    const sheetHeight = sheet?.offsetHeight ?? 0;
    const distanceThreshold = (sheetHeight * closeThresholdPercent) / 100;
    const draggedFarEnough = translateY > distanceThreshold;
    const flickedFastEnough = velocityRef.current > closeVelocityThreshold;

    // close / spring-back 양쪽 모두 translateY 0 으로 초기화 — 다음 오픈이 0 에서 시작.
    setTranslateY(0);
    if (draggedFarEnough || flickedFastEnough) {
      onClose();
    }
  }, [translateY, closeThresholdPercent, closeVelocityThreshold, onClose]);

  return {
    sheetRef,
    translateY,
    isDragging,
    prefersReducedMotion,
    onTouchStart,
    onTouchMove,
    onTouchEnd,
  };
}
