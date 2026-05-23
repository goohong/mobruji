/**
 * useAutoDismiss 단위 테스트.
 *
 * 검증 포인트:
 *   - enabled=false 면 timer 등록 / callback 호출 모두 발생하지 않는다.
 *   - enabled=true 면 delayMs 경과 후 callback 이 정확히 한 번 호출된다.
 *   - unmount 시 pending timer 가 cleanup 되어 callback 이 호출되지 않는다.
 */

import { renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useAutoDismiss } from "../useAutoDismiss";

describe("useAutoDismiss", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("enabled=false 면 delay 경과 후에도 callback 이 호출되지 않는다", () => {
    const callback = vi.fn();

    renderHook(() => useAutoDismiss(callback, 1000, false));

    vi.advanceTimersByTime(5000);

    expect(callback).not.toHaveBeenCalled();
  });

  it("enabled=true 면 delayMs 경과 후 callback 이 정확히 한 번 호출된다", () => {
    const callback = vi.fn();

    renderHook(() => useAutoDismiss(callback, 1000, true));

    // delay 직전에는 미호출.
    vi.advanceTimersByTime(999);
    expect(callback).not.toHaveBeenCalled();

    // delay 경계에서 호출.
    vi.advanceTimersByTime(1);
    expect(callback).toHaveBeenCalledTimes(1);
  });

  it("unmount 시 pending timer 가 cleanup 되어 callback 이 호출되지 않는다", () => {
    const callback = vi.fn();

    const { unmount } = renderHook(() => useAutoDismiss(callback, 1000, true));

    // delay 절반 시점에 unmount.
    vi.advanceTimersByTime(500);
    unmount();

    // 원래 delay 를 초과해도 callback 호출 없음 — clearTimeout 동작 확인.
    vi.advanceTimersByTime(5000);
    expect(callback).not.toHaveBeenCalled();
  });
});
