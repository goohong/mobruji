/**
 * Mutation/Query race 가드 테스트용 공통 helper (closes #1057).
 *
 * 배경:
 *   `useFeedbackToggleMutation.test.ts` (PR #985) 와 `useHistoryQueries-race.test.tsx`
 *   (PR #1045) 두 hook-level race test 파일이 다음 helper 를 거의 동일하게 중복 정의해
 *   왔다.
 *
 *   - `makeQueryClient()` (retry=false QueryClient)
 *   - `makeWrapper(client)` (QueryClientProvider wrapper)
 *   - `createDeferred<T>()` (controllable promise — race / unmount 케이스에서
 *     "응답 도착 시점" 을 테스트가 직접 통제)
 *
 *   본 모듈은 이 3건을 single source 로 통합. 페이지 컨텍스트 race test
 *   (`web/app/{likes,bookmarks,recommend}/page.race.test.tsx`) 의
 *   buildSong / buildWrapper / unauthorizedError 등 페이지별 helper 는 PR
 *   #1048 / #1054 머지 후 별도 사이클로 통합 예정 (open PR conflict 회피).
 *
 * 사용 예:
 *   ```ts
 *   import { renderHook } from "@testing-library/react";
 *   import {
 *     createDeferred,
 *     makeQueryClient,
 *     makeWrapper,
 *   } from "@/lib/test-helpers/race-helpers";
 *
 *   const client = makeQueryClient();
 *   const { result } = renderHook(() => useFoo(), { wrapper: makeWrapper(client) });
 *
 *   const deferred = createDeferred<FooResponse>();
 *   fooApiMock.mockReturnValueOnce(deferred.promise);
 *   // ...
 *   deferred.resolve({ ok: true });
 *   ```
 *
 * 본 모듈은 production 코드 (hook / store / page) 의존 없음 — 순수
 * @tanstack/react-query + React 만 import 한다.
 */

import {
  QueryClient,
  QueryClientProvider,
  type QueryClientConfig,
} from "@tanstack/react-query";
import { createElement, type PropsWithChildren } from "react";

import { ApiError } from "@/lib/api/client";

/**
 * race 테스트가 query/mutation retry 정책 무관하게 결정적으로 동작하도록 만드는
 * QueryClient 빌더.
 *
 * default:
 *   - `queries.retry = false` — 401 케이스에서 백오프 재시도 없이 즉시 error state.
 *   - `queries.retryDelay = 0` — `retry` 를 numeric 으로 override 한 케이스에서도
 *     wall-clock 압축 (fake timer 결합 안정성).
 *   - `mutations.retry = false` — mutation onError 가 즉시 트리거되어 store 롤백 검증
 *     타이밍을 단순화.
 *
 * override:
 *   - 호출 측이 `defaultOptions` 일부를 덮어쓰고 싶으면 `overrides` 로 전달.
 *     예: hook 본체의 `retry: 1` 정책 자체를 검증하고 싶다면
 *     `makeQueryClient({ defaultOptions: { queries: { retry: 1, retryDelay: 0 } } })`.
 *
 * 회귀 가드:
 *   - default 가 retry=false 인 이유: race 테스트가 의도하지 않은 재시도로
 *     `isError` 가 늦게 true 가 되어 `waitFor` default timeout(1000ms) 안에
 *     검증을 끝낼 수 없는 사고를 막기 위함.
 */
export function makeQueryClient(overrides?: QueryClientConfig): QueryClient {
  const overrideDefaults = overrides?.defaultOptions ?? {};
  const config: QueryClientConfig = {
    ...(overrides ?? {}),
    defaultOptions: {
      ...overrideDefaults,
      queries: {
        retry: false,
        retryDelay: 0,
        ...(overrideDefaults.queries ?? {}),
      },
      mutations: {
        retry: false,
        ...(overrideDefaults.mutations ?? {}),
      },
    },
  };
  return new QueryClient(config);
}

/**
 * `renderHook` / `render` 의 `wrapper` 옵션에 그대로 넣을 수 있는
 * QueryClientProvider 래퍼 컴포넌트 빌더.
 *
 * JSX 가 아닌 `createElement` 로 짠 이유: `.ts` 파일에서도 사용할 수 있도록
 * (호출 측이 `.test.ts` 면 JSX 가 안 열린다. 호출 측이 `.test.tsx` 라도 helper 자체는
 * 어느 쪽에서도 import 가능).
 */
export function makeWrapper(client: QueryClient) {
  return function Wrapper({ children }: PropsWithChildren) {
    return createElement(QueryClientProvider, { client }, children);
  };
}

export type Deferred<T> = {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (reason: unknown) => void;
};

/**
 * mutationFn / queryFn 응답 시점을 테스트가 직접 통제할 수 있게 하는 deferred 도우미.
 *
 * race / unmount / cross-contamination 케이스에서 "응답 도착 시점" 을 우리가 결정한다.
 *
 * 사용 패턴:
 *   ```ts
 *   const deferred = createDeferred<MyResponse>();
 *   apiMock.mockReturnValueOnce(deferred.promise);
 *
 *   // ... act 안에서 mutation.mutate() 호출, await Promise.resolve() 로 microtask flush
 *
 *   await act(async () => {
 *     deferred.resolve({ ok: true });
 *     await deferred.promise;
 *   });
 *   ```
 */
export function createDeferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

/**
 * 401 Unauthorized `ApiError` 팩토리. 페이지 race 테스트 (likes / bookmarks /
 * recommend) 가 SessionAuthGuard (PR #244) 의 401 mismatch 응답을 모사할 때 사용한다.
 *
 * `error: "UNAUTHORIZED"` 본문 payload 는 SessionAuthGuard 의 표준 응답 형식 — race
 * test 3건이 동일한 payload 로 mock 하므로 helper 단일 소스로 둔다. payload 가 다른
 * 401 케이스 (예: rate limit 으로 위장된 401) 는 호출 측이 직접 `new ApiError(...)`
 * 로 빌드한다.
 */
export function unauthorizedError(): ApiError {
  return new ApiError(401, "Unauthorized", { error: "UNAUTHORIZED" });
}
