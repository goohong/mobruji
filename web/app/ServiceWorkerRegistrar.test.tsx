/**
 * ServiceWorkerRegistrar 회귀 가드 (closes #608).
 *
 * 부수효과 전용 컴포넌트:
 *  - 마운트 시 registerServiceWorker가 1회 호출되어야 한다.
 *  - 렌더 결과는 null이어야 한다.
 *  - registerServiceWorker reject 시에도 컴포넌트는 throw하지 않는다.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, waitFor } from "@testing-library/react";

vi.mock("@/lib/registerSW", () => ({
  registerServiceWorker: vi.fn(),
}));

import { ServiceWorkerRegistrar } from "./ServiceWorkerRegistrar";
import { registerServiceWorker } from "@/lib/registerSW";

const registerServiceWorkerMock = vi.mocked(registerServiceWorker);

beforeEach(() => {
  registerServiceWorkerMock.mockReset();
});

afterEach(() => {
  cleanup();
});

describe("ServiceWorkerRegistrar", () => {
  it("마운트 시 registerServiceWorker를 1회 호출한다", async () => {
    registerServiceWorkerMock.mockResolvedValue(null);
    render(<ServiceWorkerRegistrar />);
    await waitFor(() => {
      expect(registerServiceWorkerMock).toHaveBeenCalledTimes(1);
    });
  });

  it("렌더 결과는 null이다 (부수효과 전용)", () => {
    registerServiceWorkerMock.mockResolvedValue(null);
    const { container } = render(<ServiceWorkerRegistrar />);
    expect(container.firstChild).toBeNull();
  });

  it("registerServiceWorker가 reject해도 컴포넌트는 throw하지 않는다", async () => {
    registerServiceWorkerMock.mockRejectedValue(new Error("boom"));
    expect(() => render(<ServiceWorkerRegistrar />)).not.toThrow();
    await waitFor(() => {
      expect(registerServiceWorkerMock).toHaveBeenCalledTimes(1);
    });
  });
});
