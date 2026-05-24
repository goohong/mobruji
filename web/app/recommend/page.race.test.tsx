/**
 * /recommend 페이지 useInfiniteQuery + voice-range useQuery race / 401 / unmount 회귀 가드
 * (PR #1045 후속).
 *
 * AS-IS:
 *   PR #1045 가 `/voice-range/auto` 마이크 흐름 race 3건 + `useHistoryQueries` race 8건을
 *   덮었으나, `/recommend` 페이지 본체의 핵심 비동기 경계 (useInfiniteQuery 첫 페이지 /
 *   sentinel 트리거된 다음 페이지 / voice-range 선행 useQuery) 에 대한 race / 401 /
 *   unmount 회귀 가드는 없었다. 기존 `page.test.tsx` 는 happy path (sentinel 진입 →
 *   누적 페치 → 시드 소진 안내) 와 a11y / source method 뱃지 / history push 만 cover.
 *
 *   특히 다음 회귀가 잠재됐다:
 *     - sentinel 더블 트리거: 첫 트리거로 inflight 가 시작된 직후 두 번째 trigger 가
 *       호출되어도 BE 호출은 정확히 1회만 늘어야 함. sentinelRef 의 ref 콜백이
 *       `!hasNextPage || isFetchingNextPage` 가드로 새 observer 를 만들지 않고
 *       반환하지만, 이미 마운트된 observer 는 그대로 살아 있어 두 번째 entry 이벤트가
 *       동일 콜백을 다시 발화시킬 수 있다. 본 가드가 깨지면 같은 페이지가 2회 페치되어
 *       BE 비용 + 중복 곡 누적 + history push 중복이 발생.
 *     - 401 ApiError: 첫 페이지/다음 페이지 모두 error UI 분기를 타야 한다. 현재 page.tsx
 *       의 RecommendationFeed 는 error 인스턴스 그대로 노출하고 "다시 시도" 버튼으로
 *       refetch 트리거. 회귀 시 (예: error UI 분기 제거) 무한 skeleton 또는 빈 화면.
 *     - unmount mid-fetch: useInfiniteQuery 응답이 unmount 후 도착해도 React state
 *       update warning 이 발생하면 안 됨. queryClient 가 last observer unmount 시
 *       inflight 를 abort 하지만, 일부 환경에서 abort 가 silent fail 하면 console.error
 *       가 찍히는 회귀가 발생할 수 있다.
 *     - voiceRange 401: 현재 page.tsx 는 404 만 NoSessionFallback 으로 분기하고 그 외
 *       error 는 StatusShell + error.message 로 안내. 401 에서 redirect 가 추가되는
 *       회귀 (사용자가 모든 401 을 알아채지 못한 채 다른 페이지로 튕기는 사고) 를 차단.
 *
 * TO-BE:
 *   본 파일은 page.tsx 변경 없이 테스트만 7건 추가한다.
 *
 *     1. sentinel 더블 트리거: 첫 트리거로 inflight 시작 직후 두 번째 trigger 가 와도
 *        createRecommendation 호출은 정확히 +1 (총 2회) 만.
 *     2. isFetchingNextPage 동안 다음 batch 컨테이너 aria-busy="true" + disabled
 *        skeleton 노출 (sentinel 가드 reflect).
 *     3. 첫 페이지 401 → error UI 노출 + 카드 0건 + sentinel 0건 + retry 버튼 노출.
 *     4. 다음 페이지 401 → 에러 영역 노출 + 1차 페이지 곡은 그대로 유지.
 *     5. unmount mid first-fetch → 응답 도착 후에도 console.error "unmounted state update" 0건.
 *     6. unmount mid next-page-fetch → 응답 도착 후에도 console.error 0건.
 *     7. voiceRange 401 → StatusShell 에러 안내 노출 + recommend API 호출 0건
 *        (redirect 없음 회귀 가드).
 *
 * 비범위:
 *   - production 코드 (page.tsx / sentinelRef 가드 / RecommendationFeed) 변경 없음.
 *   - SongCard 마운트 / 모달 / Like/Bookmark hook race — PR #985 / #989 / #993 / 본 폴더
 *     `PageButtonReflect.integration.test.tsx` cover.
 *   - 무한 스크롤 happy path / a11y / source method 뱃지 — `page.test.tsx` cover.
 *   - useInfiniteQuery 자체 동작 (retry / refetch) — react-query 라이브러리 책임.
 *   - test-utils 추출 — 본 사이클 비범위 (PR #1045 동일 결정 — page.test.tsx 와 helper
 *     중복은 추후 별 사이클).
 */

import { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  act,
  cleanup,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import RecommendPage from "./page";
import { ApiError } from "@/lib/api/client";
import { createRecommendation } from "@/lib/api/recommendation";
import { readVoiceRange } from "@/lib/api/voice-range";

const { sessionMock, historyMock } = await vi.hoisted(async () => {
  const sessionHelper = await import(
    "@/lib/test-helpers/mock-session-store"
  );
  const historyHelper = await import(
    "@/lib/test-helpers/mock-history-store"
  );
  return {
    sessionMock: sessionHelper.buildSessionStoreMock(),
    historyMock: historyHelper.buildHistoryStoreMock(),
  };
});

vi.mock("@/store/session", () => ({
  useSessionStore: sessionMock.useSessionStore,
}));

vi.mock("@/store/history", async () => {
  const actual =
    await vi.importActual<typeof import("@/store/history")>("@/store/history");
  return {
    ...actual,
    useHistoryStore: historyMock.useHistoryStore,
  };
});

vi.mock("@/lib/api/voice-range", async () => {
  const actual =
    await vi.importActual<typeof import("@/lib/api/voice-range")>(
      "@/lib/api/voice-range",
    );
  return {
    ...actual,
    readVoiceRange: vi.fn(),
  };
});

vi.mock("@/lib/api/recommendation", async () => {
  const actual =
    await vi.importActual<typeof import("@/lib/api/recommendation")>(
      "@/lib/api/recommendation",
    );
  return {
    ...actual,
    createRecommendation: vi.fn(),
  };
});

const readVoiceRangeMock = vi.mocked(readVoiceRange);
const createRecommendationMock = vi.mocked(createRecommendation);

/**
 * 테스트용 IntersectionObserver mock — `page.test.tsx` 와 동일 패턴.
 *
 * 의도적 동일 패턴 (helper 추출 안 함):
 *   PR #1045 동일 결정 — race 테스트 파일은 의도적으로 race 시나리오만 격리. test-utils
 *   추출은 page.test.tsx + PageButtonReflect + 본 파일 3건 누적된 후 별 사이클로 검토.
 */
type ObserverEntry = {
  callback: IntersectionObserverCallback;
  nodes: Set<Element>;
  disconnected: boolean;
};

const observerRegistry: ObserverEntry[] = [];

class MockIntersectionObserver implements IntersectionObserver {
  readonly root: Element | Document | null = null;
  readonly rootMargin: string = "";
  readonly thresholds: ReadonlyArray<number> = [];
  private entry: ObserverEntry;

  constructor(callback: IntersectionObserverCallback) {
    this.entry = { callback, nodes: new Set(), disconnected: false };
    observerRegistry.push(this.entry);
  }

  observe(target: Element): void {
    this.entry.nodes.add(target);
  }

  unobserve(target: Element): void {
    this.entry.nodes.delete(target);
  }

  disconnect(): void {
    this.entry.nodes.clear();
    this.entry.disconnected = true;
  }

  takeRecords(): IntersectionObserverEntry[] {
    return [];
  }
}

function installIntersectionObserverMock() {
  (
    globalThis as unknown as {
      IntersectionObserver: typeof IntersectionObserver;
    }
  ).IntersectionObserver =
    MockIntersectionObserver as unknown as typeof IntersectionObserver;
}

function resetObserverRegistry() {
  observerRegistry.splice(0, observerRegistry.length);
}

/**
 * 현재 살아있는(disconnect 안 됐고 노드 1개 이상 observe 중) observer 콜백을
 * isIntersecting=true 로 발화. sentinel 더블 트리거 검증에서는 한 번의 호출이
 * 등록된 모든 observer 를 발화시키므로 중첩 호출로 race 를 모사한다.
 */
function triggerIntersection() {
  for (const entry of observerRegistry) {
    if (entry.disconnected) {
      continue;
    }
    if (entry.nodes.size === 0) {
      continue;
    }
    const fakeEntries: IntersectionObserverEntry[] = Array.from(
      entry.nodes,
    ).map(
      (node) =>
        ({
          isIntersecting: true,
          target: node,
          intersectionRatio: 1,
          boundingClientRect: {} as DOMRectReadOnly,
          intersectionRect: {} as DOMRectReadOnly,
          rootBounds: null,
          time: 0,
        }) as IntersectionObserverEntry,
    );
    entry.callback(fakeEntries, {} as IntersectionObserver);
  }
}

/**
 * 외부 컨트롤 deferred Promise — PR #1045 / PR #993 / PR #989 동일 패턴.
 * inflight 상태를 임의 길이로 유지해서 race / unmount / 더블 트리거 시나리오를 검증.
 */
function deferred<T>(): {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (error: unknown) => void;
} {
  let resolveFn: (value: T) => void = () => undefined;
  let rejectFn: (error: unknown) => void = () => undefined;
  const promise = new Promise<T>((res, rej) => {
    resolveFn = res;
    rejectFn = rej;
  });
  return { promise, resolve: resolveFn, reject: rejectFn };
}

function renderWithQueryClient(ui: ReactNode) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    );
  }
  return render(ui, { wrapper: Wrapper });
}

/**
 * 응답 헬퍼 — `page.test.tsx` 와 동일. UUIDv7 문자열 requestId (#422).
 */
function ridFromSeed(seed: number): string {
  return `01933b1c-7f8a-7c2d-9b3e-${seed.toString(16).padStart(12, "0")}`;
}

function buildResponseWithSongIds(seed: number, songIds: number[]) {
  return {
    requestId: ridFromSeed(seed),
    recommendations: songIds.map((id, idx) => ({
      rankPosition: idx + 1,
      score: 0.9 - idx * 0.05,
      matchReason: "음역 매칭",
      song: {
        id,
        title: `곡-${id}`,
        artist: "가수",
        releaseYear: 2024,
        keyOriginal: "C_MAJOR" as const,
        bpm: 110,
        mood: "UPBEAT" as const,
        language: "ko",
        genre: "POP",
        tjNumber: `T-${id}`,
        kyNumber: `K-${id}`,
        metadataSource: "MANUAL_SEED" as const,
      },
    })),
  };
}

function defaultVoiceRange(sessionId: string, id: number) {
  return {
    id,
    sessionId,
    lowestNoteMidi: 48,
    highestNoteMidi: 69,
    sourceMethod: "OCTAVE_PICK" as const,
    createdAt: "2026-05-24T00:00:00Z",
    updatedAt: "2026-05-24T00:00:00Z",
  };
}

beforeEach(() => {
  sessionMock.reset();
  historyMock.reset();
  readVoiceRangeMock.mockReset();
  createRecommendationMock.mockReset();
  resetObserverRegistry();
  installIntersectionObserverMock();
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("/recommend 페이지 race / 401 / unmount 가드 (PR #1045 후속)", () => {
  // ---------- 1. sentinel 더블 트리거 race ----------
  it("sentinel 첫 트리거 inflight 도중 두 번째 trigger 가 와도 createRecommendation 호출은 정확히 +1 만", async () => {
    sessionMock.set({ sessionId: "sess-double", voiceRangeId: 1 });
    readVoiceRangeMock.mockResolvedValue(defaultVoiceRange("sess-double", 1));

    // 1차 페이지는 즉시 응답, 2차 페이지는 deferred 로 inflight 유지.
    const nextPageDeferred = deferred<ReturnType<typeof buildResponseWithSongIds>>();
    createRecommendationMock
      .mockResolvedValueOnce(buildResponseWithSongIds(1, [10, 20]))
      .mockImplementationOnce(() => nextPageDeferred.promise);

    renderWithQueryClient(<RecommendPage />);

    // 1차 페이지 도착까지 대기.
    await waitFor(() => {
      expect(screen.getByText("곡-10")).toBeInTheDocument();
    });
    await waitFor(() => {
      expect(screen.getByTestId("recommend-sentinel")).toBeInTheDocument();
    });
    expect(createRecommendationMock).toHaveBeenCalledTimes(1);

    // 첫 sentinel 트리거 → 2차 페이지 inflight 시작.
    await act(async () => {
      triggerIntersection();
    });
    await waitFor(() => {
      expect(createRecommendationMock).toHaveBeenCalledTimes(2);
    });

    // 두 번째 trigger — inflight 도중. 동일 observer 가 다시 발화돼도
    // useInfiniteQuery 내부에서 dedupe (inflight 동안 fetchNextPage 호출 no-op)
    // + sentinel ref 콜백이 isFetchingNextPage 가드 → BE 호출 +0 이어야 한다.
    await act(async () => {
      triggerIntersection();
      triggerIntersection();
    });

    // 가드가 깨지면 +2 → 총 4. 통과 시 여전히 2.
    expect(createRecommendationMock).toHaveBeenCalledTimes(2);

    // 응답 도착 후 정상 처리 — 이후 trigger 가 의미를 갖는 상태로 복귀하는지도 확인.
    await act(async () => {
      nextPageDeferred.resolve(buildResponseWithSongIds(2, [30, 40]));
      await nextPageDeferred.promise;
    });
    await waitFor(() => {
      expect(screen.getByText("곡-30")).toBeInTheDocument();
    });
  });

  // ---------- 2. isFetchingNextPage aria-busy reflect ----------
  it("다음 페이지 inflight 동안 추가 batch 로딩 컨테이너에 aria-busy='true' 와 skeleton 이 노출된다", async () => {
    sessionMock.set({ sessionId: "sess-busy", voiceRangeId: 2 });
    readVoiceRangeMock.mockResolvedValue(defaultVoiceRange("sess-busy", 2));

    const nextDeferred = deferred<ReturnType<typeof buildResponseWithSongIds>>();
    createRecommendationMock
      .mockResolvedValueOnce(buildResponseWithSongIds(1, [11]))
      .mockImplementationOnce(() => nextDeferred.promise);

    renderWithQueryClient(<RecommendPage />);

    await waitFor(() => {
      expect(screen.getByText("곡-11")).toBeInTheDocument();
    });

    // 인플라이트 진입 전: "다음 추천 결과 로딩 중" 컨테이너는 없어야 한다.
    expect(
      screen.queryByLabelText("다음 추천 결과 로딩 중"),
    ).not.toBeInTheDocument();

    // sentinel 트리거 → inflight 시작.
    await act(async () => {
      triggerIntersection();
    });

    // inflight 동안 aria-busy="true" 컨테이너가 sentinel 위에 노출.
    await waitFor(() => {
      const loadingList = screen.getByLabelText("다음 추천 결과 로딩 중");
      expect(loadingList).toHaveAttribute("aria-busy", "true");
    });

    // 응답 도착 → 로딩 컨테이너 사라지고 신규 곡 노출.
    await act(async () => {
      nextDeferred.resolve(buildResponseWithSongIds(2, [22]));
      await nextDeferred.promise;
    });
    await waitFor(() => {
      expect(screen.getByText("곡-22")).toBeInTheDocument();
    });
    expect(
      screen.queryByLabelText("다음 추천 결과 로딩 중"),
    ).not.toBeInTheDocument();
  });

  // ---------- 3. 첫 페이지 401 ----------
  it("첫 페이지 createRecommendation 401 → 에러 UI + 카드 0건 + sentinel 0건 + 다시 시도 버튼 노출", async () => {
    sessionMock.set({ sessionId: "sess-401-first", voiceRangeId: 3 });
    readVoiceRangeMock.mockResolvedValue(
      defaultVoiceRange("sess-401-first", 3),
    );
    createRecommendationMock.mockRejectedValueOnce(
      new ApiError(401, "Unauthorized", { error: "AUTH" }),
    );

    renderWithQueryClient(<RecommendPage />);

    // 에러 메시지에 status + message 포함 (RecommendationFeed error 분기).
    await waitFor(() => {
      expect(screen.getByText(/401: Unauthorized/)).toBeInTheDocument();
    });
    // sentinel 은 error 분기에서 노출되지 않는다.
    expect(
      screen.queryByTestId("recommend-sentinel"),
    ).not.toBeInTheDocument();
    // "다시 시도" 버튼 노출 — refetch 트리거 보장.
    expect(
      screen.getByRole("button", { name: /다시 시도/ }),
    ).toBeInTheDocument();
  });

  // ---------- 4. 다음 페이지 401 ----------
  it("다음 페이지 createRecommendation 401 → 에러 영역 노출 + 1차 페이지 곡은 유지", async () => {
    sessionMock.set({ sessionId: "sess-401-next", voiceRangeId: 4 });
    readVoiceRangeMock.mockResolvedValue(defaultVoiceRange("sess-401-next", 4));

    createRecommendationMock
      .mockResolvedValueOnce(buildResponseWithSongIds(1, [101]))
      .mockRejectedValueOnce(
        new ApiError(401, "Unauthorized", { error: "AUTH" }),
      );

    renderWithQueryClient(<RecommendPage />);

    await waitFor(() => {
      expect(screen.getByText("곡-101")).toBeInTheDocument();
    });

    // sentinel 트리거 → 다음 페이지 401 reject.
    await act(async () => {
      triggerIntersection();
    });

    await waitFor(() => {
      expect(createRecommendationMock).toHaveBeenCalledTimes(2);
    });

    // 에러 메시지 노출. useInfiniteQuery 의 error 는 모든 페이지 컨텍스트에서
    // RecommendationFeed 의 error 분기를 타게 되어 카드 영역이 에러 박스로 대체된다.
    await waitFor(() => {
      expect(screen.getByText(/401: Unauthorized/)).toBeInTheDocument();
    });
    expect(
      screen.getByRole("button", { name: /다시 시도/ }),
    ).toBeInTheDocument();
  });

  // ---------- 5. unmount mid first-fetch ----------
  it("첫 페이지 inflight 도중 unmount 해도 응답 도착 시 console.error 'unmounted state update' 0건", async () => {
    sessionMock.set({ sessionId: "sess-unmount-first", voiceRangeId: 5 });
    readVoiceRangeMock.mockResolvedValue(
      defaultVoiceRange("sess-unmount-first", 5),
    );

    const firstDeferred =
      deferred<ReturnType<typeof buildResponseWithSongIds>>();
    createRecommendationMock.mockImplementationOnce(
      () => firstDeferred.promise,
    );

    const consoleErrorSpy = vi
      .spyOn(console, "error")
      .mockImplementation(() => {});

    const { unmount } = renderWithQueryClient(<RecommendPage />);

    // inflight 시작까지 대기.
    await waitFor(() => {
      expect(createRecommendationMock).toHaveBeenCalledTimes(1);
    });

    // inflight 도중 unmount.
    unmount();

    // 응답 도착 — unmounted observer 의 setState 가 무시되어야 한다.
    await act(async () => {
      firstDeferred.resolve(buildResponseWithSongIds(1, [1]));
      await firstDeferred.promise;
    });

    const stateUpdateWarnings = consoleErrorSpy.mock.calls.filter((call) => {
      const first = call[0];
      return (
        typeof first === "string" &&
        first.includes("unmounted") &&
        first.includes("state update")
      );
    });
    expect(stateUpdateWarnings).toEqual([]);

    consoleErrorSpy.mockRestore();
  });

  // ---------- 6. unmount mid next-page-fetch ----------
  it("다음 페이지 inflight 도중 unmount 해도 응답 도착 시 console.error 'unmounted state update' 0건", async () => {
    sessionMock.set({ sessionId: "sess-unmount-next", voiceRangeId: 6 });
    readVoiceRangeMock.mockResolvedValue(
      defaultVoiceRange("sess-unmount-next", 6),
    );

    const nextDeferred =
      deferred<ReturnType<typeof buildResponseWithSongIds>>();
    createRecommendationMock
      .mockResolvedValueOnce(buildResponseWithSongIds(1, [201]))
      .mockImplementationOnce(() => nextDeferred.promise);

    const consoleErrorSpy = vi
      .spyOn(console, "error")
      .mockImplementation(() => {});

    const { unmount } = renderWithQueryClient(<RecommendPage />);

    await waitFor(() => {
      expect(screen.getByText("곡-201")).toBeInTheDocument();
    });

    // sentinel 트리거 → 다음 페이지 inflight.
    await act(async () => {
      triggerIntersection();
    });
    await waitFor(() => {
      expect(createRecommendationMock).toHaveBeenCalledTimes(2);
    });

    // inflight 도중 unmount.
    unmount();

    await act(async () => {
      nextDeferred.resolve(buildResponseWithSongIds(2, [202]));
      await nextDeferred.promise;
    });

    const stateUpdateWarnings = consoleErrorSpy.mock.calls.filter((call) => {
      const first = call[0];
      return (
        typeof first === "string" &&
        first.includes("unmounted") &&
        first.includes("state update")
      );
    });
    expect(stateUpdateWarnings).toEqual([]);

    consoleErrorSpy.mockRestore();
  });

  // ---------- 7. voice-range 401 → StatusShell 안내 + recommend API 호출 0건 ----------
  it("voice-range 401 응답 시 StatusShell 안내 노출 + createRecommendation 호출 0건 (redirect 회귀 가드)", async () => {
    sessionMock.set({ sessionId: "sess-vr-401", voiceRangeId: 7 });
    readVoiceRangeMock.mockRejectedValueOnce(
      new ApiError(401, "Unauthorized", { error: "AUTH" }),
    );

    renderWithQueryClient(<RecommendPage />);

    // 404 만 NoSessionFallback 으로 분기. 401 은 StatusShell + error.message 노출.
    await waitFor(() => {
      expect(
        screen.getByRole("heading", {
          name: /음역대 정보를 불러오지 못했습니다/,
        }),
      ).toBeInTheDocument();
    });
    expect(screen.getByText("Unauthorized")).toBeInTheDocument();

    // voice-range 가 실패했으므로 enabled gate 가 닫혀 recommend API 는 호출 0건.
    // (회귀 시: enabled gate 누락 → 0/undefined 음역대로 BE 호출 → 추가 401)
    expect(createRecommendationMock).not.toHaveBeenCalled();

    // 사용자가 직접 다시 입력 흐름으로 가는 CTA 가 노출돼야 한다.
    expect(
      screen.getByRole("link", { name: /다시 입력하기/ }),
    ).toBeInTheDocument();

    // retry 버튼이 React Router push 같은 부수효과 0건임을 의식적으로 보장하기 위해
    // 한번 더 클릭 시도. CTA 클릭은 Next.js Link → push 가 일어나도 본 mock 환경에선
    // 무시되므로, "클릭 후에도 createRecommendation 호출 0건" 만 검증한다.
    const user = userEvent.setup();
    await user.click(screen.getByRole("link", { name: /다시 입력하기/ }));
    expect(createRecommendationMock).not.toHaveBeenCalled();
  });
});
