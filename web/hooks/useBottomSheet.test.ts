/**
 * useBottomSheet 훅 단위 테스트 (closes #1696).
 *
 * `docs/features/ui-ux-redesign-pr-4-bottom-sheet.md` §5-3 회귀 가드 매트릭스
 * G6~G9·G11 의 제스처 분기를 hook 레벨에서 정밀 검증한다. happy-dom 은 실제
 * TouchEvent timeStamp / offsetHeight 를 제공하지 않으므로, 핸들러에 직접 mock
 * 이벤트(touches + timeStamp)를 주입하고 sheetRef.current 에 offsetHeight/
 * scrollTop 을 세팅해 거리·velocity·scroll 우선 분기를 결정적으로 트리거한다.
 */

import { afterEach, describe, expect, it, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";

import { useBottomSheet } from "./useBottomSheet";

type TouchLike = { clientY: number };

function touchEvent(clientY: number, timeStamp: number) {
  return {
    touches: [{ clientY } as TouchLike],
    timeStamp,
  } as unknown as Parameters<
    ReturnType<typeof useBottomSheet>["onTouchMove"]
  >[0];
}

/** sheetRef.current 를 offsetHeight/scrollTop 을 가진 가짜 엘리먼트로 세팅. */
function attachSheet(
  ref: ReturnType<typeof useBottomSheet>["sheetRef"],
  { offsetHeight = 400, scrollTop = 0 } = {},
) {
  ref.current = { offsetHeight, scrollTop } as HTMLDivElement;
}

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("useBottomSheet (#1696)", () => {
  it("G6: 드래그 거리가 시트 높이 25% 를 넘으면 onClose 가 호출된다", () => {
    const onClose = vi.fn();
    const { result } = renderHook(() =>
      useBottomSheet({ isOpen: true, onClose }),
    );
    attachSheet(result.current.sheetRef, { offsetHeight: 400 });

    act(() => result.current.onTouchStart(touchEvent(0, 0)));
    // 400 * 25% = 100 → 120px 드래그 = 임계 초과.
    act(() => result.current.onTouchMove(touchEvent(120, 16)));
    act(() => result.current.onTouchEnd());

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("G7: 임계 미만 드래그는 onClose 미호출 + translateY 0 으로 spring back", () => {
    const onClose = vi.fn();
    const { result } = renderHook(() =>
      useBottomSheet({ isOpen: true, onClose }),
    );
    attachSheet(result.current.sheetRef, { offsetHeight: 400 });

    act(() => result.current.onTouchStart(touchEvent(0, 0)));
    // 천천히 40px 만 (임계 100px 미달, velocity 낮음).
    act(() => result.current.onTouchMove(touchEvent(20, 100)));
    act(() => result.current.onTouchMove(touchEvent(40, 300)));
    act(() => result.current.onTouchEnd());

    expect(onClose).not.toHaveBeenCalled();
    expect(result.current.translateY).toBe(0);
  });

  it("G8: 거리 미달이어도 velocity > 0.5px/ms 면 onClose 가 호출된다", () => {
    const onClose = vi.fn();
    const { result } = renderHook(() =>
      useBottomSheet({ isOpen: true, onClose }),
    );
    attachSheet(result.current.sheetRef, { offsetHeight: 400 });

    act(() => result.current.onTouchStart(touchEvent(0, 0)));
    // 30px (임계 100px 미달) 을 10ms 만에 → velocity 3px/ms 로 빠른 flick.
    act(() => result.current.onTouchMove(touchEvent(20, 5)));
    act(() => result.current.onTouchMove(touchEvent(30, 10)));
    act(() => result.current.onTouchEnd());

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("G9: 시트 내부 scroll 위치가 0 이 아니면 swipe 가 비활성화된다", () => {
    const onClose = vi.fn();
    const { result } = renderHook(() =>
      useBottomSheet({ isOpen: true, onClose }),
    );
    // scrollTop > 0 → touchstart 가 드래그를 시작하지 않음.
    attachSheet(result.current.sheetRef, { offsetHeight: 400, scrollTop: 50 });

    act(() => result.current.onTouchStart(touchEvent(0, 0)));
    act(() => result.current.onTouchMove(touchEvent(300, 16)));
    act(() => result.current.onTouchEnd());

    expect(onClose).not.toHaveBeenCalled();
    expect(result.current.isDragging).toBe(false);
    expect(result.current.translateY).toBe(0);
  });

  it("위로(음수) 끄는 동작은 translateY 가 0 으로 clamp 된다", () => {
    const onClose = vi.fn();
    const { result } = renderHook(() =>
      useBottomSheet({ isOpen: true, onClose }),
    );
    attachSheet(result.current.sheetRef);

    act(() => result.current.onTouchStart(touchEvent(100, 0)));
    act(() => result.current.onTouchMove(touchEvent(40, 16))); // 위로 -60
    expect(result.current.translateY).toBe(0);

    act(() => result.current.onTouchEnd());
    expect(onClose).not.toHaveBeenCalled();
  });

  it("G11: prefers-reduced-motion 시 드래그 제스처가 비활성화된다", () => {
    vi.stubGlobal(
      "matchMedia",
      vi.fn().mockReturnValue({ matches: true }) as unknown,
    );
    const onClose = vi.fn();
    const { result } = renderHook(() =>
      useBottomSheet({ isOpen: true, onClose }),
    );
    attachSheet(result.current.sheetRef, { offsetHeight: 400 });

    expect(result.current.prefersReducedMotion).toBe(true);

    act(() => result.current.onTouchStart(touchEvent(0, 0)));
    act(() => result.current.onTouchMove(touchEvent(300, 16)));
    act(() => result.current.onTouchEnd());

    expect(result.current.isDragging).toBe(false);
    expect(result.current.translateY).toBe(0);
    expect(onClose).not.toHaveBeenCalled();
  });

  it("드래그 중 translateY 가 손가락을 따라가고 touchend 후 0 으로 초기화된다", () => {
    const onClose = vi.fn();
    const { result } = renderHook(() =>
      useBottomSheet({ isOpen: true, onClose }),
    );
    attachSheet(result.current.sheetRef, { offsetHeight: 400 });

    act(() => result.current.onTouchStart(touchEvent(0, 0)));
    act(() => result.current.onTouchMove(touchEvent(40, 16)));
    expect(result.current.translateY).toBe(40);
    expect(result.current.isDragging).toBe(true);

    // 임계 초과 드래그로 close → translateY 0 초기화 + isDragging 해제.
    act(() => result.current.onTouchMove(touchEvent(150, 32)));
    act(() => result.current.onTouchEnd());
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(result.current.translateY).toBe(0);
    expect(result.current.isDragging).toBe(false);
  });
});
