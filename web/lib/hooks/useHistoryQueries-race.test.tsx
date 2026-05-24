/**
 * recommendation-history / voice-range-history React Query race 가드
 * (PR #1033 후속 — /voice-range/auto mutation race 5건 가드와 동일 패턴 확장).
 *
 * 배경:
 *   /history 페이지 (`web/app/history/page.tsx`) 가 inline `useQuery` 로
 *   recommendationHistory + voiceRangeHistory 두 BE endpoint 를 호출한다. 본 테스트는
 *   같은 queryFn 시그니처를 hook 으로 추출하지 않고 query 동작 자체의 race 회귀를
 *   가드한다 (테스트만 추가 — production 코드 변경 없음).
 *
 * 검증 범위 (각 hook 별 동일 3 케이스):
 *
 *   1) refetch 도중 두 번째 호출 race condition
 *      - 첫 호출이 fresh state 에서 refetch() → 두 번째 호출이 inflight 로 진입.
 *      - 두 번째 호출이 settle 하기 전까지 data 는 직전 fresh 값 (stale 노출 X).
 *      - 두 번째 응답 도착 후에야 새 값으로 갱신됨.
 *      - 본 가드가 깨지면 사용자가 새로고침 후에도 이전 stale 데이터를 본다.
 *
 *   2) unmount 중 inflight query — observer 가 비면 fetch 가 abort 된다.
 *      - QueryClient 가 last observer unmount 시 inflight AbortController.abort() 호출.
 *      - 회귀로 abort 가 빠지면 memory leak (FetchEvent + Promise + dangling state).
 *
 *   3) 401 ApiError 응답 시 retry: 1 정책에서 2회 호출 + 사용자 가시 에러
 *      - SessionAuthGuard (PR #244) 가 X-Session-Id mismatch 시 401 반환.
 *      - retry: 1 정책 — 첫 시도 + 재시도 1회 = 총 2회 호출 후 error 노출.
 *      - error state 가 즉시 노출되어 호출 측이 fallback 분기 가능.
 *
 *   4) enabled: Boolean(sessionId) gating — sessionId null 인 동안 호출 X.
 *      - HistoryPage 의 zustand persist hydration 전에 BE 호출 → 401 fallback 회귀 방지.
 *
 * 비범위 (의도적으로 검증 안 함):
 *   - HistoryPage 자체 통합 (별도 page.test.tsx 가 source-of-truth UI 분기 책임).
 *   - API client 모듈 자체 동작 (별도 recommendationHistory.test.ts /
 *     voiceRangeHistory.test.ts 가 fetch 호출 검증).
 *   - production hook 추출 (본 PR 은 테스트만 추가).
 */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { useQuery } from "@tanstack/react-query";

import { ApiError } from "@/lib/api/client";
import {
  createDeferred,
  makeQueryClient,
  makeWrapper,
} from "@/lib/test-helpers/race-helpers";

// API client 를 mock — 실제 fetch 발생 시 happy-dom 환경에서 noise / wall-clock
// 변동성이 race 검증을 흐린다. 본 테스트 범위는 React Query 동작이지 fetch 가 아님.
vi.mock("@/lib/api/recommendationHistory", () => ({
  readRecommendationHistory: vi.fn(),
}));
vi.mock("@/lib/api/voiceRangeHistory", () => ({
  readVoiceRangeHistory: vi.fn(),
}));

import { readRecommendationHistory } from "@/lib/api/recommendationHistory";
import { readVoiceRangeHistory } from "@/lib/api/voiceRangeHistory";

const readRecommendationHistoryMock = vi.mocked(readRecommendationHistory);
const readVoiceRangeHistoryMock = vi.mocked(readVoiceRangeHistory);

const TEST_SESSION_ID = "sess-race-guard";

// QueryClient / Wrapper / Deferred helper 는 `@/lib/test-helpers/race-helpers` 단일
// 소스 (PR #1057 refactor). default 가 queries.retry=false + queries.retryDelay=0 +
// mutations.retry=false — race 친화. 호출 측이 hook 의 retry: 1 정책 자체를 검증하고
// 싶다면 makeQueryClient({ defaultOptions: { queries: { retry: 1, retryDelay: 0 } } })
// 로 override 가능.

/**
 * HistoryPage 의 useQuery 호출 시그니처를 그대로 모사하는 helper hook.
 * production 코드와 일치하도록 queryKey / queryFn / enabled / retry: 1 까지 동일.
 */
function useRecommendationHistoryQuery(sessionId: string | null) {
  return useQuery({
    queryKey: ["recommendation-history", sessionId],
    queryFn: ({ signal }) => readRecommendationHistory(sessionId!, signal),
    enabled: Boolean(sessionId),
    retry: 1,
  });
}

function useVoiceRangeHistoryQuery(sessionId: string | null) {
  return useQuery({
    queryKey: ["voice-range-history", sessionId],
    queryFn: ({ signal }) => readVoiceRangeHistory(sessionId!, signal),
    enabled: Boolean(sessionId),
    retry: 1,
  });
}

beforeEach(() => {
  readRecommendationHistoryMock.mockReset();
  readVoiceRangeHistoryMock.mockReset();
});

afterEach(() => {
  vi.clearAllMocks();
});

describe("useRecommendationHistoryQuery race 가드", () => {
  it("refetch 도중 두 번째 호출 시 stale data 반환 없이 fresh await 후 갱신된다", async () => {
    // 첫 호출은 inflight 로 유지 (resolve 안 함) → refetch 시 React Query 가
    // inflight 를 abort 하지 않더라도 두 번째 호출의 응답을 기다린다.
    const firstResponse = {
      recommendationHistoryResponses: [
        {
          requestId: "01933b1c-7f8a-7c2d-9b3e-000000000001",
          sessionId: TEST_SESSION_ID,
          voiceRangeLow: 52,
          voiceRangeHigh: 70,
          mood: null,
          preferredBpm: null,
          requestedAt: "2026-05-21T08:00:00",
          recommendations: [],
        },
      ],
    };
    const secondResponse = {
      recommendationHistoryResponses: [
        {
          requestId: "01933b1c-7f8a-7c2d-9b3e-000000000002",
          sessionId: TEST_SESSION_ID,
          voiceRangeLow: 52,
          voiceRangeHigh: 70,
          mood: null,
          preferredBpm: null,
          requestedAt: "2026-05-21T09:00:00",
          recommendations: [],
        },
      ],
    };

    // 첫 호출은 빠르게 resolve, 두 번째 호출은 deferred.
    const deferred = createDeferred<typeof secondResponse>();
    readRecommendationHistoryMock
      .mockImplementationOnce(async () => firstResponse)
      .mockImplementationOnce(() => deferred.promise);

    const client = makeQueryClient();
    const { result } = renderHook(
      () => useRecommendationHistoryQuery(TEST_SESSION_ID),
      { wrapper: makeWrapper(client) },
    );

    // 첫 호출 완료 — data=firstResponse 노출.
    await waitFor(() => {
      expect(result.current.data).toEqual(firstResponse);
    });

    // refetch 트리거 → 두 번째 호출 inflight.
    void result.current.refetch();

    // 두 번째 호출이 inflight 로 진입할 때까지 대기.
    await waitFor(() => {
      expect(readRecommendationHistoryMock).toHaveBeenCalledTimes(2);
    });

    // refetch 중에는 data 가 직전 fresh 값 (firstResponse) 그대로 — stale data 노출 X.
    // 두 번째 호출의 응답으로 덮어쓰여지기 전까지 사용자는 직전 fresh 값을 본다.
    expect(result.current.data).toEqual(firstResponse);

    // 두 번째 응답 도착 → data 가 secondResponse 로 갱신.
    deferred.resolve(secondResponse);
    await waitFor(() => {
      expect(result.current.data).toEqual(secondResponse);
      expect(result.current.isFetching).toBe(false);
    });
  });

  it("inflight query 도중 unmount 하면 queryFn 의 AbortSignal 이 abort 된다", async () => {
    let capturedSignal: AbortSignal | undefined;
    const deferred = createDeferred<{
      recommendationHistoryResponses: never[];
    }>();
    readRecommendationHistoryMock.mockImplementation((_id, signal) => {
      capturedSignal = signal;
      return deferred.promise;
    });

    const client = makeQueryClient();
    const { unmount } = renderHook(
      () => useRecommendationHistoryQuery(TEST_SESSION_ID),
      { wrapper: makeWrapper(client) },
    );

    await waitFor(() => {
      expect(capturedSignal).toBeDefined();
    });
    expect(capturedSignal!.aborted).toBe(false);

    // last observer unmount → React Query 가 inflight AbortController.abort() 호출.
    // (gcTime: 0 으로 캐시 즉시 제거 → observer 0 + cache GC 시점에 abort)
    unmount();

    await waitFor(() => {
      expect(capturedSignal!.aborted).toBe(true);
    });

    // resolve 해도 unmounted observer 의 setState 는 호출되지 않는다 — leak 없음.
    deferred.resolve({ recommendationHistoryResponses: [] });
  });

  it("401 ApiError 응답 시 retry: 1 정책에서 2회 호출되고 error 가 노출된다", async () => {
    // production HistoryPage 의 useQuery 가 retry: 1 사용 — 본 helper hook 도 동일.
    // QueryClient default retry 보다 hook 의 retry: 1 이 우선 적용 (React Query 규약).
    // 401 발생 시 첫 시도 + 재시도 1회 = 총 2회 호출 → error 노출 — 무한 재시도 X.
    const unauthorized = new ApiError(401, "Unauthorized", { error: "auth" });
    readRecommendationHistoryMock.mockRejectedValue(unauthorized);

    const client = makeQueryClient();
    const { result } = renderHook(
      () => useRecommendationHistoryQuery(TEST_SESSION_ID),
      { wrapper: makeWrapper(client) },
    );

    await waitFor(() => {
      expect(result.current.isError).toBe(true);
    });

    // hook 본체의 retry: 1 = 첫 시도 + 재시도 1회 = 2회 호출. 무한 재시도 X.
    expect(readRecommendationHistoryMock).toHaveBeenCalledTimes(2);
    expect(result.current.error).toBeInstanceOf(ApiError);
    expect((result.current.error as ApiError).status).toBe(401);
    // 401 응답이므로 data 는 비어 있어야 한다 — stale cache 무효화 확인.
    expect(result.current.data).toBeUndefined();
  });
});

describe("useVoiceRangeHistoryQuery race 가드", () => {
  it("refetch 도중 두 번째 호출 시 stale data 반환 없이 fresh await 후 갱신된다", async () => {
    const firstResponse = {
      voiceRangeSnapshotResponses: [
        {
          id: 1,
          lowMidi: 48,
          highMidi: 69,
          lowestNoteName: "C3",
          highestNoteName: "A4",
          sourceMethod: "MIC_MEASURE" as const,
          measuredAt: "2026-05-21T08:00:00",
        },
      ],
    };
    const secondResponse = {
      voiceRangeSnapshotResponses: [
        {
          id: 1,
          lowMidi: 48,
          highMidi: 69,
          lowestNoteName: "C3",
          highestNoteName: "A4",
          sourceMethod: "MIC_MEASURE" as const,
          measuredAt: "2026-05-21T08:00:00",
        },
        {
          id: 2,
          lowMidi: 50,
          highMidi: 71,
          lowestNoteName: "D3",
          highestNoteName: "B4",
          sourceMethod: "MIC_MEASURE" as const,
          measuredAt: "2026-05-21T09:00:00",
        },
      ],
    };

    const deferred = createDeferred<typeof secondResponse>();
    readVoiceRangeHistoryMock
      .mockImplementationOnce(async () => firstResponse)
      .mockImplementationOnce(() => deferred.promise);

    const client = makeQueryClient();
    const { result } = renderHook(
      () => useVoiceRangeHistoryQuery(TEST_SESSION_ID),
      { wrapper: makeWrapper(client) },
    );

    await waitFor(() => {
      expect(result.current.data).toEqual(firstResponse);
    });

    void result.current.refetch();

    // 두 번째 호출이 inflight 로 진입할 때까지 대기.
    await waitFor(() => {
      expect(readVoiceRangeHistoryMock).toHaveBeenCalledTimes(2);
    });

    // refetch 중에는 직전 fresh data 그대로 — stale data 노출 안 됨.
    // (두 번째 호출의 응답으로 덮어쓰여지기 전까지 사용자는 직전 fresh 값을 본다.)
    expect(result.current.data).toEqual(firstResponse);

    deferred.resolve(secondResponse);
    await waitFor(() => {
      expect(result.current.data).toEqual(secondResponse);
      expect(result.current.isFetching).toBe(false);
    });
  });

  it("inflight query 도중 unmount 하면 queryFn 의 AbortSignal 이 abort 된다", async () => {
    let capturedSignal: AbortSignal | undefined;
    const deferred = createDeferred<{ voiceRangeSnapshotResponses: never[] }>();
    readVoiceRangeHistoryMock.mockImplementation((_id, signal) => {
      capturedSignal = signal;
      return deferred.promise;
    });

    const client = makeQueryClient();
    const { unmount } = renderHook(
      () => useVoiceRangeHistoryQuery(TEST_SESSION_ID),
      { wrapper: makeWrapper(client) },
    );

    await waitFor(() => {
      expect(capturedSignal).toBeDefined();
    });
    expect(capturedSignal!.aborted).toBe(false);

    unmount();

    await waitFor(() => {
      expect(capturedSignal!.aborted).toBe(true);
    });

    deferred.resolve({ voiceRangeSnapshotResponses: [] });
  });

  it("401 ApiError 응답 시 retry: 1 정책에서 2회 호출되고 error 가 노출된다", async () => {
    const unauthorized = new ApiError(401, "Unauthorized", { error: "auth" });
    readVoiceRangeHistoryMock.mockRejectedValue(unauthorized);

    const client = makeQueryClient();
    const { result } = renderHook(
      () => useVoiceRangeHistoryQuery(TEST_SESSION_ID),
      { wrapper: makeWrapper(client) },
    );

    await waitFor(() => {
      expect(result.current.isError).toBe(true);
    });

    expect(readVoiceRangeHistoryMock).toHaveBeenCalledTimes(2);
    expect(result.current.error).toBeInstanceOf(ApiError);
    expect((result.current.error as ApiError).status).toBe(401);
    expect(result.current.data).toBeUndefined();
  });
});

describe("history queries — enabled gating", () => {
  // sessionId 가 null 인 동안 queryFn 호출이 일어나면 안 된다 — sessionId hydration
  // 전에 BE 가 호출되어 401 으로 떨어지는 회귀 가드. HistoryPage 의 enabled: Boolean(sessionId)
  // 정책이 깨졌을 때 본 테스트가 즉시 실패한다.
  it("sessionId 가 null 이면 readRecommendationHistory 가 호출되지 않는다", async () => {
    readRecommendationHistoryMock.mockResolvedValue({
      recommendationHistoryResponses: [],
    });

    const client = makeQueryClient();
    renderHook(() => useRecommendationHistoryQuery(null), {
      wrapper: makeWrapper(client),
    });

    // 일정 시간 기다려도 호출 0건.
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(readRecommendationHistoryMock).not.toHaveBeenCalled();
  });

  it("sessionId 가 null 이면 readVoiceRangeHistory 가 호출되지 않는다", async () => {
    readVoiceRangeHistoryMock.mockResolvedValue({
      voiceRangeSnapshotResponses: [],
    });

    const client = makeQueryClient();
    renderHook(() => useVoiceRangeHistoryQuery(null), {
      wrapper: makeWrapper(client),
    });

    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(readVoiceRangeHistoryMock).not.toHaveBeenCalled();
  });
});
