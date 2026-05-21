/**
 * registerServiceWorker 단위 테스트.
 *
 * happy-dom 환경에서 navigator.serviceWorker가 없는 점을 이용해
 *  - 미지원 환경 → null 반환
 *  - mock 주입 후 force 옵션 → register 호출 확인
 *  - register reject → null 반환 + throw 하지 않음
 */

import { afterEach, describe, expect, it, vi } from "vitest";

import { SERVICE_WORKER_PATH, registerServiceWorker } from "./registerSW";

type SWContainerMock = {
  register: ReturnType<typeof vi.fn>;
};

function installServiceWorkerMock(register: SWContainerMock["register"]): void {
  Object.defineProperty(navigator, "serviceWorker", {
    configurable: true,
    value: { register },
  });
}

function removeServiceWorkerMock(): void {
  if ("serviceWorker" in navigator) {
    // @ts-expect-error - happy-dom navigator는 delete 가능
    delete (navigator as Navigator).serviceWorker;
  }
}

afterEach(() => {
  removeServiceWorkerMock();
  vi.restoreAllMocks();
});

describe("registerServiceWorker", () => {
  it("serviceWorker API가 없으면 null을 반환한다", async () => {
    removeServiceWorkerMock();
    const result = await registerServiceWorker({ force: true });
    expect(result).toBeNull();
  });

  it("force 옵션으로 register를 호출하고 registration을 반환한다", async () => {
    const fakeRegistration = { scope: "/" } as ServiceWorkerRegistration;
    const register = vi.fn().mockResolvedValue(fakeRegistration);
    installServiceWorkerMock(register);

    const result = await registerServiceWorker({ force: true });

    expect(register).toHaveBeenCalledWith(SERVICE_WORKER_PATH, { scope: "/" });
    expect(result).toBe(fakeRegistration);
  });

  it("register가 실패해도 throw하지 않고 null을 반환한다", async () => {
    const register = vi.fn().mockRejectedValue(new Error("boom"));
    installServiceWorkerMock(register);
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});

    const result = await registerServiceWorker({ force: true });

    expect(result).toBeNull();
    expect(warn).toHaveBeenCalled();
  });
});
