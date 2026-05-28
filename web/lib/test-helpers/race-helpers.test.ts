/**
 * race-helpers 자체 단위 테스트 (closes #1057).
 *
 * 본 helper 는 race / unmount / 401 등 hook-level 회귀 가드의 토대이므로 helper
 * 자체에 회귀가 발생하면 상위 race test 7+ 케이스가 동시에 무너진다. 따라서 helper
 * 본체의 default + override 분기 + deferred lifecycle 을 가드한다.
 *
 * 검증 범위:
 *   1) makeQueryClient default — queries.retry / queries.retryDelay / mutations.retry
 *      모두 race 친화 default 가 적용된다.
 *   2) makeQueryClient override — 호출 측이 일부 옵션만 덮어써도 default 의 나머지는
 *      유지된다 (회귀 시 호출 측 race test 가 의도와 다르게 retry 한다).
 *   3) makeWrapper — children 을 QueryClientProvider 안에 감싸 useQueryClient() 가
 *      주어진 client 인스턴스를 회수한다.
 *   4) createDeferred — resolve/reject 양방향 lifecycle + 동일 promise 가 두 번
 *      settle 되지 않는 native Promise 의미를 helper 가 깨지 않는다.
 */

import { describe, expect, it } from "vitest";
import { renderHook } from "@testing-library/react";
import { useQueryClient } from "@tanstack/react-query";

import {
  createDeferred,
  makeQueryClient,
  makeWrapper,
} from "./race-helpers";

describe("makeQueryClient", () => {
  it("default 옵션이 race 친화 (queries/mutations 모두 retry=false)", () => {
    const client = makeQueryClient();
    const defaults = client.getDefaultOptions();
    expect(defaults.queries?.retry).toBe(false);
    expect(defaults.queries?.retryDelay).toBe(0);
    expect(defaults.mutations?.retry).toBe(false);
  });

  it("queries.retry override 시 나머지 default (retryDelay / mutations) 는 유지", () => {
    const client = makeQueryClient({
      defaultOptions: {
        queries: { retry: 1 },
      },
    });
    const defaults = client.getDefaultOptions();
    expect(defaults.queries?.retry).toBe(1);
    // retryDelay 는 default 0 그대로 — 호출 측이 명시 안 한 옵션이 사라지면 안 됨.
    expect(defaults.queries?.retryDelay).toBe(0);
    // mutations 도 영향 없음.
    expect(defaults.mutations?.retry).toBe(false);
  });

  it("mutations.retry override 시 queries default 는 유지", () => {
    const client = makeQueryClient({
      defaultOptions: {
        mutations: { retry: 2 },
      },
    });
    const defaults = client.getDefaultOptions();
    expect(defaults.mutations?.retry).toBe(2);
    expect(defaults.queries?.retry).toBe(false);
    expect(defaults.queries?.retryDelay).toBe(0);
  });
});

describe("makeWrapper", () => {
  it("QueryClientProvider 로 감싼 children 안에서 useQueryClient 가 주어진 client 인스턴스를 회수한다", () => {
    const client = makeQueryClient();
    const { result } = renderHook(() => useQueryClient(), {
      wrapper: makeWrapper(client),
    });
    expect(result.current).toBe(client);
  });
});

describe("createDeferred", () => {
  it("resolve 시 promise 가 주어진 값으로 fulfilled 된다", async () => {
    const deferred = createDeferred<{ ok: boolean }>();
    deferred.resolve({ ok: true });
    await expect(deferred.promise).resolves.toEqual({ ok: true });
  });

  it("reject 시 promise 가 주어진 reason 으로 rejected 된다", async () => {
    const deferred = createDeferred<unknown>();
    const error = new Error("boom");
    deferred.reject(error);
    await expect(deferred.promise).rejects.toBe(error);
  });

  it("동일 promise 가 한 번만 settle 된다 (resolve 후 reject 는 무시)", async () => {
    const deferred = createDeferred<string>();
    deferred.resolve("first");
    deferred.reject(new Error("ignored"));
    await expect(deferred.promise).resolves.toBe("first");
  });

  it("호출 측이 resolve 호출 전이라도 promise 는 pending 상태", async () => {
    const deferred = createDeferred<number>();
    // race-condition fence — Promise.race 로 즉시 0 을 settle 하는 promise 와 경쟁시켜
    // helper promise 가 아직 pending 인지 확인.
    const sentinel = Promise.resolve("sentinel");
    const winner = await Promise.race([deferred.promise, sentinel]);
    expect(winner).toBe("sentinel");
    deferred.resolve(42);
    await expect(deferred.promise).resolves.toBe(42);
  });
});
