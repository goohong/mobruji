/**
 * useAutoDismissMessage 단위 테스트 (rev 19 / #295 항목 1 후속).
 *
 * 검증 포인트:
 *   1) setMessage 호출 후 durationMs 경과 시 message 가 null 로 자동 정리.
 *   2) 새 메시지가 세팅되면 이전 타이머는 취소되고 새 타이머 기준으로 재계산.
 *   3) clear() 호출 시 즉시 null 이 되고 보류 중인 타이머가 발사되어도 noop.
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
});
