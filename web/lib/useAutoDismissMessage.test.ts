/**
 * useAutoDismissMessage 단위 테스트 (rev 19 / #295 항목 1 후속).
 *
 * 검증 포인트:
 *   1) setMessage 호출 후 durationMs 경과 시 message 가 null 로 자동 정리.
 *   2) 새 메시지가 세팅되면 이전 타이머는 취소되고 새 타이머 기준으로 재계산.
 *   3) clear() 호출 시 즉시 null 이 되고 보류 중인 타이머가 발사되어도 noop.
 *   4) message 가 falsy (null / "") 일 때는 setTimeout 자체가 등록되지 않는다
 *      — PR #408 close 후속 회귀 가드 (enabled=false 등가).
 *   5) durationMs 가 0 또는 음수인 경계값에서도 등록은 정상이고 정리는 즉시 트리거
 *      — PR #408 close 후속 회귀 가드 (delay 경계).
 *   6) unmount 시 보류 타이머가 clearTimeout 으로 정리된다 (메모리 누수 가드)
 *      — PR #408 close 후속 회귀 가드.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";

import { useAutoDismissMessage } from "./useAutoDismissMessage";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("useAutoDismissMessage", () => {
  it("setMessage 후 durationMs 가 지나면 message 가 null 로 정리된다", () => {
    const { result } = renderHook(() => useAutoDismissMessage(3000));

    act(() => {
      result.current.setMessage("실패했어요");
    });
    expect(result.current.message).toBe("실패했어요");

    act(() => {
      vi.advanceTimersByTime(3000);
    });
    expect(result.current.message).toBeNull();
  });

  it("새 메시지로 갱신되면 이전 타이머가 취소되고 새 메시지 기준으로 다시 카운트한다", () => {
    const { result } = renderHook(() => useAutoDismissMessage(3000));

    act(() => {
      result.current.setMessage("첫 번째");
    });

    // 2초 경과 — 아직 첫 번째 메시지 유지 중.
    act(() => {
      vi.advanceTimersByTime(2000);
    });
    expect(result.current.message).toBe("첫 번째");

    // 새 메시지로 교체. 이전 타이머는 cleanup 되어야 한다.
    act(() => {
      result.current.setMessage("두 번째");
    });
    expect(result.current.message).toBe("두 번째");

    // 새 메시지 기준으로 2초 더 — 누적 4초이지만 메시지는 살아 있어야 한다.
    act(() => {
      vi.advanceTimersByTime(2000);
    });
    expect(result.current.message).toBe("두 번째");

    // 새 메시지 기준 3초 도달 시 정리.
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(result.current.message).toBeNull();
  });

  it("clear() 호출 시 즉시 null 이 되고 이후 타이머가 발사되어도 다시 set 되지 않는다", () => {
    const { result } = renderHook(() => useAutoDismissMessage(3000));

    act(() => {
      result.current.setMessage("실패했어요");
    });
    expect(result.current.message).toBe("실패했어요");

    act(() => {
      result.current.clear();
    });
    expect(result.current.message).toBeNull();

    // 잔존 타이머가 있었다면 여기서 다시 set 되었을 텐데, clear 도 새 effect 를
    // 트리거하지 않으므로 여전히 null.
    act(() => {
      vi.advanceTimersByTime(5000);
    });
    expect(result.current.message).toBeNull();
  });

  // ---------------------------------------------------------------------------
  // PR #408 close 후속 회귀 가드 (2026-05-24)
  //
  // 본체 (`useAutoDismissMessage.ts`) 는 다음 경계에서 silently 잘 동작하지만,
  // 향후 리팩토링/조건 추가 시 회귀를 막기 위한 명시적 가드 케이스.
  //   - falsy message (enabled=false 등가) → setTimeout 자체 미등록
  //   - 경계값 durationMs (0, 음수) → setTimeout 호출은 되지만 즉시 정리
  //   - unmount → 보류 중인 타이머가 clearTimeout 으로 정리
  // ---------------------------------------------------------------------------

  it("message 가 falsy (초기 null / 빈 문자열) 일 때는 setTimeout 이 등록되지 않는다", () => {
    const setTimeoutSpy = vi.spyOn(window, "setTimeout");

    const { result } = renderHook(() => useAutoDismissMessage(3000));

    // 초기 상태 (message=null) — 등록 0 회.
    expect(setTimeoutSpy).not.toHaveBeenCalled();

    // 빈 문자열로 세팅 — 본체 `if (!message)` early return 으로 여전히 0 회.
    act(() => {
      result.current.setMessage("");
    });
    expect(setTimeoutSpy).not.toHaveBeenCalled();
    expect(result.current.message).toBe("");

    // 시간이 흘러도 setMessage(null) 콜백이 호출되지 않으므로 여전히 "".
    act(() => {
      vi.advanceTimersByTime(10_000);
    });
    expect(result.current.message).toBe("");

    setTimeoutSpy.mockRestore();
  });

  it("durationMs=0 경계: setTimeout 은 등록되고 즉시 정리된다", () => {
    const setTimeoutSpy = vi.spyOn(window, "setTimeout");

    const { result } = renderHook(() => useAutoDismissMessage(0));

    act(() => {
      result.current.setMessage("실패했어요");
    });

    // setTimeout 은 등록 — delay 0 으로 호출되어야 한다.
    expect(setTimeoutSpy).toHaveBeenCalledTimes(1);
    expect(setTimeoutSpy.mock.calls[0]?.[1]).toBe(0);

    // 0ms 진행으로 즉시 정리.
    act(() => {
      vi.advanceTimersByTime(0);
    });
    expect(result.current.message).toBeNull();

    setTimeoutSpy.mockRestore();
  });

  it("durationMs 음수 경계: setTimeout 호출은 정상이며 즉시 정리된다 (브라우저는 음수→0 으로 clamp)", () => {
    const setTimeoutSpy = vi.spyOn(window, "setTimeout");

    const { result } = renderHook(() => useAutoDismissMessage(-100));

    act(() => {
      result.current.setMessage("실패했어요");
    });

    // 본체는 음수 가드 없이 그대로 전달 — setTimeout 자체 호출은 되어야 한다.
    expect(setTimeoutSpy).toHaveBeenCalledTimes(1);
    expect(setTimeoutSpy.mock.calls[0]?.[1]).toBe(-100);

    // 음수 delay 는 즉시 큐잉되므로 0ms 진행만으로 정리.
    act(() => {
      vi.advanceTimersByTime(0);
    });
    expect(result.current.message).toBeNull();

    setTimeoutSpy.mockRestore();
  });

  it("unmount 시 보류 중인 타이머가 clearTimeout 으로 정리된다 (메모리 누수 가드)", () => {
    const clearTimeoutSpy = vi.spyOn(window, "clearTimeout");

    const { result, unmount } = renderHook(() => useAutoDismissMessage(3000));

    act(() => {
      result.current.setMessage("실패했어요");
    });

    // 타이머 보류 중인 상태에서 unmount.
    const clearCallsBeforeUnmount = clearTimeoutSpy.mock.calls.length;
    unmount();

    // useEffect cleanup 으로 clearTimeout 이 1 회 추가 호출되어야 한다.
    expect(clearTimeoutSpy.mock.calls.length).toBe(clearCallsBeforeUnmount + 1);

    // unmount 후 durationMs 가 지나도 — hook 은 이미 해제되어 setMessage 콜백이
    // 호출되지 않아야 하므로 setState-on-unmounted 경고도 발생하지 않아야 한다.
    // (vitest 가 React 경고를 console.error 로 띄우면 test 가 실패한다)
    act(() => {
      vi.advanceTimersByTime(3000);
    });

    clearTimeoutSpy.mockRestore();
  });
});
