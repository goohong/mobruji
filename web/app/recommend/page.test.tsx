/**
 * 추천 결과 페이지 테스트.
 *
 * 이슈 #321 (2026-05-22): "다른 곡 추천받기" 버튼 → **무한 스크롤** 전환.
 * 테스트 범위:
 *  - sessionId 없음 → NoSessionFallback.
 *  - sessionId 존재 + voice-range 성공 + 첫 페이지 응답 → 카드 리스트 렌더.
 *  - 같은 props 재렌더에도 첫 페이지 페치는 정확히 1회.
 *  - sentinel 진입(IntersectionObserver 흉내) → fetchNextPage 호출 → 누적 excludeSongIds 전달.
 *  - 두 번째 sentinel 진입 → 1차+2차 페이지 곡 ID 누적 전달.
 *  - 빈 응답(첫 페이지) → fallback CTA, sentinel 없음.
 *  - 첫 페이지 비어있지 않고 두 번째가 비면 시드 소진 안내 + sentinel 사라짐.
 *  - history.appendRecommendation 호출 검증.
 *  - voice-range source method 헤더 뱃지 + MIC_MEASURE 링크.
 *  - a11y: NoSession / 카드 리스트 / 빈 응답 상태.
 *
 * IntersectionObserver mock 전략:
 *   - happy-dom 은 IntersectionObserver 를 제공하지 않으므로 globalThis 에
 *     수동 polyfill 을 둔다. observer 인스턴스마다 콜백을 캡처해서 테스트가
 *     `triggerIntersection()` 으로 sentinel 진입을 흉내낼 수 있다.
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
import { readVoiceRange } from "@/lib/api/voice-range";
import { createRecommendation } from "@/lib/api/recommendation";
import { expectNoA11yViolations } from "@/lib/test-helpers/a11y";

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
 * 테스트용 IntersectionObserver mock.
 *
 * - new IntersectionObserver(cb, opts) 마다 인스턴스를 컬렉션에 push.
 * - observe(node) 호출 시 node 를 인스턴스에 등록.
 * - `triggerIntersection()` 호출 시 등록된 모든 observer 콜백을 isIntersecting=true 로 발화.
 *
 * 컴포넌트는 sentinel ref 콜백에서 hasNextPage/isFetchingNextPage 체크로 observer
 * 생성을 게이트한다. 즉 "관찰 중인 observer 가 1개 이상" 일 때 trigger 가 의미를 갖는다.
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
  // happy-dom 이 기본 제공하는 IntersectionObserver 가 있더라도 본 mock 으로 치환해
  // observer 인스턴스를 우리가 직접 트리거할 수 있게 한다. 타입은 노출 시그니처만 맞으면 충분.
  (globalThis as unknown as { IntersectionObserver: typeof IntersectionObserver }).IntersectionObserver =
    MockIntersectionObserver as unknown as typeof IntersectionObserver;
}

function resetObserverRegistry() {
  observerRegistry.splice(0, observerRegistry.length);
}

/**
 * 현재 살아있는(observe 중이고 disconnect 안 된) observer 콜백을 isIntersecting=true 로 발화.
 */
function triggerIntersection() {
  for (const entry of observerRegistry) {
    if (entry.disconnected) {
      continue;
    }
    if (entry.nodes.size === 0) {
      continue;
    }
    const fakeEntries: IntersectionObserverEntry[] = Array.from(entry.nodes).map(
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

function renderWithQueryClient(ui: ReactNode) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  }
  return render(ui, { wrapper: Wrapper });
}

/**
 * sessionMock 의 appendExcluded 를 실제 누적 로직으로 wire.
 * 무한 스크롤 흐름에서 다음 페이지 호출이 직전 페이지 곡 ID 를 누적해 보내는지
 * 검증하기 위해 필수.
 */
function wireAppendExcluded() {
  const append = vi.fn((ids: number[]) => {
    const merged = new Set(sessionMock.state().excludedSongIds);
    for (const id of ids) merged.add(id);
    sessionMock.set({ excludedSongIds: Array.from(merged) });
  });
  sessionMock.set({ appendExcluded: append });
  return append;
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

/**
 * 추천 응답 헬퍼 — 곡 ID 리스트로 응답을 생성.
 *
 * issue #422: BE `requestId` 가 UUIDv7 문자열로 전환됨에 따라 헬퍼 시그니처도
 * `string` 으로 통일. 기존 정수 seed 입력 호출처가 많아 동일 호출부를 유지하도록
 * `ridFromSeed()` 로 결정적 UUID 문자열을 만들어 넣는다.
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

describe("RecommendPage", () => {
  it("sessionId가 없으면 음역대 입력 안내(FALLBACK)를 렌더한다", () => {
    renderWithQueryClient(<RecommendPage />);
    expect(
      screen.getByRole("heading", {
        name: /음역대가 아직 등록되지 않았습니다/,
      }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: /음역대 입력하러 가기/ }),
    ).toBeInTheDocument();
  });

  it("sessionId가 있으면 voice-range 조회 → 첫 페이지 추천을 받아 카드 리스트를 렌더한다", async () => {
    sessionMock.set({ sessionId: "sess-abc", voiceRangeId: 42 });

    readVoiceRangeMock.mockResolvedValue({
      id: 42,
      sessionId: "sess-abc",
      lowestNoteMidi: 48,
      highestNoteMidi: 69,
      sourceMethod: "OCTAVE_PICK",
      createdAt: "2026-05-21T00:00:00Z",
      updatedAt: "2026-05-21T00:00:00Z",
    });
    createRecommendationMock.mockResolvedValueOnce(
      buildResponseWithSongIds(100, [1, 2]),
    );

    renderWithQueryClient(<RecommendPage />);

    await waitFor(() => {
      expect(readVoiceRangeMock).toHaveBeenCalledWith("sess-abc");
    });

    await waitFor(() => {
      expect(createRecommendationMock).toHaveBeenCalledWith({
        sessionId: "sess-abc",
        voiceRangeLow: 48,
        voiceRangeHigh: 69,
        excludeSongIds: [],
      });
    });

    await waitFor(() => {
      expect(screen.getByText("곡-1")).toBeInTheDocument();
    });
    expect(screen.getByText("곡-2")).toBeInTheDocument();
    // closes #1764 — 결과 영역에 종합 점수순 정렬 기준 한 줄 안내가 노출된다.
    expect(screen.getByTestId("recommend-sort-criteria")).toHaveTextContent(
      "음역 적합도·분위기·인기 등을 종합한 점수순으로 정렬했어요.",
    );
  });

  // ---------- 결과 정렬 기준 선택 (closes #1765) ----------
  it("정렬 칩(음역 적합순/분위기 적합순) 선택 시 결과 순서와 기준 캡션이 바뀐다", async () => {
    const user = userEvent.setup();
    sessionMock.set({ sessionId: "sess-sort", voiceRangeId: 7 });

    readVoiceRangeMock.mockResolvedValue({
      id: 7,
      sessionId: "sess-sort",
      lowestNoteMidi: 48,
      highestNoteMidi: 69,
      sourceMethod: "OCTAVE_PICK",
      createdAt: "2026-05-21T00:00:00Z",
      updatedAt: "2026-05-21T00:00:00Z",
    });

    // 세 곡의 종합/음역/분위기 적합이 서로 달라 정렬 축마다 순서가 달라진다.
    //   composite(도착순): 1, 2, 3
    //   voiceFit desc    : 2(0.9), 3(0.5), 1(0.2)
    //   moodFit desc     : 1(0.9), 3(0.5), 2(0.1)
    const baseSong = {
      artist: "가수",
      releaseYear: 2024,
      keyOriginal: "C_MAJOR" as const,
      bpm: 110,
      mood: "UPBEAT" as const,
      language: "ko",
      genre: "POP",
      metadataSource: "MANUAL_SEED" as const,
    };
    createRecommendationMock.mockResolvedValueOnce({
      requestId: ridFromSeed(700),
      recommendations: [
        {
          rankPosition: 1,
          score: 0.9,
          matchReason: "음역 매칭",
          voiceFit: 0.2,
          moodFit: 0.9,
          song: { id: 1, title: "곡-1", tjNumber: "T-1", kyNumber: "K-1", ...baseSong },
        },
        {
          rankPosition: 2,
          score: 0.8,
          matchReason: "음역 매칭",
          voiceFit: 0.9,
          moodFit: 0.1,
          song: { id: 2, title: "곡-2", tjNumber: "T-2", kyNumber: "K-2", ...baseSong },
        },
        {
          rankPosition: 3,
          score: 0.7,
          matchReason: "음역 매칭",
          voiceFit: 0.5,
          moodFit: 0.5,
          song: { id: 3, title: "곡-3", tjNumber: "T-3", kyNumber: "K-3", ...baseSong },
        },
      ],
    });

    renderWithQueryClient(<RecommendPage />);

    await waitFor(() => {
      expect(screen.getByText("곡-1")).toBeInTheDocument();
    });

    const titleOrder = () =>
      screen.getAllByText(/^곡-\d+$/).map((el) => el.textContent);

    // 기본은 종합 추천순(도착 순서) + 종합 정렬 캡션.
    expect(titleOrder()).toEqual(["곡-1", "곡-2", "곡-3"]);
    expect(screen.getByTestId("recommend-sort-criteria")).toHaveTextContent(
      "음역 적합도·분위기·인기 등을 종합한 점수순으로 정렬했어요.",
    );

    // 음역 적합순 → voiceFit 내림차순 재정렬 + 캡션 갱신.
    await user.click(screen.getByRole("radio", { name: "음역 적합순" }));
    expect(titleOrder()).toEqual(["곡-2", "곡-3", "곡-1"]);
    expect(screen.getByTestId("recommend-sort-criteria")).toHaveTextContent(
      "내 음역대에 잘 맞는 곡부터 정렬했어요.",
    );

    // 분위기 적합순 → moodFit 내림차순 재정렬 + 캡션 갱신.
    await user.click(screen.getByRole("radio", { name: "분위기 적합순" }));
    expect(titleOrder()).toEqual(["곡-1", "곡-3", "곡-2"]);
    expect(screen.getByTestId("recommend-sort-criteria")).toHaveTextContent(
      "고른 분위기에 잘 맞는 곡부터 정렬했어요.",
    );

    // 다시 추천순 → 도착 순서 복귀.
    await user.click(screen.getByRole("radio", { name: "추천순" }));
    expect(titleOrder()).toEqual(["곡-1", "곡-2", "곡-3"]);

    // 정렬은 클라이언트 재정렬이라 추가 추천 호출이 없다.
    expect(createRecommendationMock).toHaveBeenCalledTimes(1);
  });

  // ---------- 분위기/나이대 필터 (roadmap-mood-age-ui) ----------
  it("분위기 칩 선택 시 mood 를 포함해 추천을 다시 요청한다", async () => {
    const user = userEvent.setup();
    sessionMock.set({ sessionId: "sess-mood", voiceRangeId: 5 });

    readVoiceRangeMock.mockResolvedValue({
      id: 5,
      sessionId: "sess-mood",
      lowestNoteMidi: 48,
      highestNoteMidi: 69,
      sourceMethod: "OCTAVE_PICK",
      createdAt: "2026-05-21T00:00:00Z",
      updatedAt: "2026-05-21T00:00:00Z",
    });
    createRecommendationMock.mockResolvedValue(
      buildResponseWithSongIds(300, [1]),
    );

    renderWithQueryClient(<RecommendPage />);

    // 최초: mood 없이 호출 (하위호환).
    await waitFor(() => {
      expect(createRecommendationMock).toHaveBeenNthCalledWith(1, {
        sessionId: "sess-mood",
        voiceRangeLow: 48,
        voiceRangeHigh: 69,
        excludeSongIds: [],
      });
    });

    // 필터는 접이식 "추천 다듬기" 안에 있으므로 먼저 펼친다.
    await user.click(screen.getByRole("button", { name: /추천 다듬기/ }));
    await user.click(screen.getByRole("button", { name: "감성적인" }));

    // mood 선택 → queryKey 변경 → mood 포함 재요청.
    await waitFor(() => {
      expect(createRecommendationMock).toHaveBeenCalledWith({
        sessionId: "sess-mood",
        voiceRangeLow: 48,
        voiceRangeHigh: 69,
        excludeSongIds: [],
        mood: "EMOTIONAL",
      });
    });
  });

  it("나이대 칩 선택 시 ageGroup 을 포함해 추천을 다시 요청한다", async () => {
    const user = userEvent.setup();
    sessionMock.set({ sessionId: "sess-age", voiceRangeId: 6 });

    readVoiceRangeMock.mockResolvedValue({
      id: 6,
      sessionId: "sess-age",
      lowestNoteMidi: 48,
      highestNoteMidi: 69,
      sourceMethod: "OCTAVE_PICK",
      createdAt: "2026-05-21T00:00:00Z",
      updatedAt: "2026-05-21T00:00:00Z",
    });
    createRecommendationMock.mockResolvedValue(
      buildResponseWithSongIds(301, [1]),
    );

    renderWithQueryClient(<RecommendPage />);

    await waitFor(() => {
      expect(createRecommendationMock).toHaveBeenCalledTimes(1);
    });

    await user.click(screen.getByRole("button", { name: /추천 다듬기/ }));
    await user.click(screen.getByRole("button", { name: "30대" }));

    await waitFor(() => {
      expect(createRecommendationMock).toHaveBeenCalledWith({
        sessionId: "sess-age",
        voiceRangeLow: 48,
        voiceRangeHigh: 69,
        excludeSongIds: [],
        ageGroup: "THIRTIES",
      });
    });
  });

  // ---------- 의도 모드 (P-E 안전곡, #1600) ----------
  it("'안 망할 곡' 의도 모드 선택 시 persona=P-E 를 포함해 추천을 다시 요청한다", async () => {
    const user = userEvent.setup();
    sessionMock.set({ sessionId: "sess-pe", voiceRangeId: 7 });

    readVoiceRangeMock.mockResolvedValue({
      id: 7,
      sessionId: "sess-pe",
      lowestNoteMidi: 48,
      highestNoteMidi: 69,
      sourceMethod: "OCTAVE_PICK",
      createdAt: "2026-05-21T00:00:00Z",
      updatedAt: "2026-05-21T00:00:00Z",
    });
    createRecommendationMock.mockResolvedValue(
      buildResponseWithSongIds(302, [1]),
    );

    renderWithQueryClient(<RecommendPage />);

    // 최초: persona 없이 호출 (하위호환).
    await waitFor(() => {
      expect(createRecommendationMock).toHaveBeenNthCalledWith(1, {
        sessionId: "sess-pe",
        voiceRangeLow: 48,
        voiceRangeHigh: 69,
        excludeSongIds: [],
      });
    });

    // 의도 모드 토글은 결과 아래 "다른 방식으로 추천받기" 모드 그룹에 상시 노출
    // (#1712 — 더 이상 접이식 필터 패널 안에 있지 않다).
    await user.click(
      screen.getByRole("button", { name: /안 망할 곡 추천받기/ }),
    );

    // 의도 모드 선택 → queryKey 변경 → persona 포함 재요청.
    await waitFor(() => {
      expect(createRecommendationMock).toHaveBeenCalledWith({
        sessionId: "sess-pe",
        voiceRangeLow: 48,
        voiceRangeHigh: 69,
        excludeSongIds: [],
        persona: "P-E",
      });
    });
  });

  // ---------- 스와이프 뷰 토글 (#1489) ----------
  it("'스와이프' 토글 시 한 곡씩 카드(덱)로 전환되고 진행 표시가 보인다", async () => {
    const user = userEvent.setup();
    sessionMock.set({ sessionId: "sess-swipe", voiceRangeId: 9 });

    readVoiceRangeMock.mockResolvedValue({
      id: 9,
      sessionId: "sess-swipe",
      lowestNoteMidi: 48,
      highestNoteMidi: 69,
      sourceMethod: "OCTAVE_PICK",
      createdAt: "2026-05-21T00:00:00Z",
      updatedAt: "2026-05-21T00:00:00Z",
    });
    createRecommendationMock.mockResolvedValueOnce(
      buildResponseWithSongIds(200, [1, 2]),
    );
    // 스와이프 덱은 남은 카드가 적으면 다음 batch 를 프리페치한다. 두 번째 응답을
    // 비워(seed 소진) 추가 페치가 깔끔히 멈추도록 한다.
    createRecommendationMock.mockResolvedValue(buildResponseWithSongIds(201, []));

    renderWithQueryClient(<RecommendPage />);

    await waitFor(() => {
      expect(screen.getByText("곡-1")).toBeInTheDocument();
    });

    await user.click(screen.getByRole("radio", { name: "스와이프" }));

    // 덱 진행 표시 + 첫 곡만 노출 (리스트의 두 번째 곡 카드는 사라진다).
    expect(screen.getByTestId("swipe-progress")).toHaveTextContent("1 / 2");
    expect(screen.getByTestId("swipe-card")).toBeInTheDocument();
  });

  // ---------- 무한 스크롤 자동 트리거 회귀 가드 ----------
  it("같은 props로 재렌더해도 첫 페이지 페치는 정확히 1회만 호출된다", async () => {
    sessionMock.set({ sessionId: "sess-stable", voiceRangeId: 7 });

    readVoiceRangeMock.mockResolvedValue({
      id: 7,
      sessionId: "sess-stable",
      lowestNoteMidi: 50,
      highestNoteMidi: 70,
      sourceMethod: "OCTAVE_PICK",
      createdAt: "2026-05-21T00:00:00Z",
      updatedAt: "2026-05-21T00:00:00Z",
    });

    createRecommendationMock.mockResolvedValue(
      buildResponseWithSongIds(101, [101]),
    );

    const { rerender } = renderWithQueryClient(<RecommendPage />);

    await waitFor(() => {
      expect(createRecommendationMock).toHaveBeenCalledTimes(1);
    });
    await waitFor(() => {
      expect(screen.getByText("곡-101")).toBeInTheDocument();
    });

    rerender(<RecommendPage />);
    rerender(<RecommendPage />);
    rerender(<RecommendPage />);

    await act(async () => {
      await Promise.resolve();
    });

    expect(createRecommendationMock).toHaveBeenCalledTimes(1);
  });

  // ---------- 무한 스크롤 페치 ----------
  describe("무한 스크롤 (#321)", () => {
    it("sentinel 진입 시 fetchNextPage가 호출되고 이전 페이지 곡 ID들이 excludeSongIds에 누적 전달된다", async () => {
      sessionMock.set({ sessionId: "sess-scroll", voiceRangeId: 11 });
      wireAppendExcluded();

      readVoiceRangeMock.mockResolvedValue({
        id: 11,
        sessionId: "sess-scroll",
        lowestNoteMidi: 50,
        highestNoteMidi: 70,
        sourceMethod: "OCTAVE_PICK",
        createdAt: "2026-05-21T00:00:00Z",
        updatedAt: "2026-05-21T00:00:00Z",
      });

      createRecommendationMock
        .mockResolvedValueOnce(buildResponseWithSongIds(1, [10, 20]))
        .mockResolvedValueOnce(buildResponseWithSongIds(2, [30, 40]));

      renderWithQueryClient(<RecommendPage />);

      // 1차 페이지: 빈 excludeSongIds.
      await waitFor(() => {
        expect(createRecommendationMock).toHaveBeenNthCalledWith(1, {
          sessionId: "sess-scroll",
          voiceRangeLow: 50,
          voiceRangeHigh: 70,
          excludeSongIds: [],
        });
      });
      await waitFor(() => {
        expect(screen.getByText("곡-10")).toBeInTheDocument();
      });

      // 응답 곡 ID 가 store 에 누적되었는지 확인 (appendExcluded wire 덕분).
      await waitFor(() => {
        expect(sessionMock.state().excludedSongIds).toEqual([10, 20]);
      });

      // sentinel 진입 흉내 → fetchNextPage 트리거.
      // sentinel 노드는 컴포넌트 렌더 후에 mount 되므로 testid 로 존재 확인 후 trigger.
      await waitFor(() => {
        expect(screen.getByTestId("recommend-sentinel")).toBeInTheDocument();
      });

      await act(async () => {
        triggerIntersection();
      });

      await waitFor(() => {
        expect(createRecommendationMock).toHaveBeenCalledTimes(2);
      });
      expect(createRecommendationMock).toHaveBeenNthCalledWith(2, {
        sessionId: "sess-scroll",
        voiceRangeLow: 50,
        voiceRangeHigh: 70,
        excludeSongIds: [10, 20],
      });

      await waitFor(() => {
        expect(screen.getByText("곡-30")).toBeInTheDocument();
      });
      expect(screen.getByText("곡-40")).toBeInTheDocument();
      // 1차 곡들도 여전히 노출 (무한 스크롤은 append, replace 가 아니다).
      expect(screen.getByText("곡-10")).toBeInTheDocument();
    });

    it("두 번째 sentinel 진입에서 1차+2차 페이지 곡 ID들이 모두 누적 전달된다", async () => {
      sessionMock.set({ sessionId: "sess-accum", voiceRangeId: 22 });
      wireAppendExcluded();

      readVoiceRangeMock.mockResolvedValue({
        id: 22,
        sessionId: "sess-accum",
        lowestNoteMidi: 48,
        highestNoteMidi: 72,
        sourceMethod: "OCTAVE_PICK",
        createdAt: "2026-05-21T00:00:00Z",
        updatedAt: "2026-05-21T00:00:00Z",
      });

      createRecommendationMock
        .mockResolvedValueOnce(buildResponseWithSongIds(1, [100, 200]))
        .mockResolvedValueOnce(buildResponseWithSongIds(2, [300, 400]))
        .mockResolvedValueOnce(buildResponseWithSongIds(3, [500, 600]));

      renderWithQueryClient(<RecommendPage />);

      await waitFor(() => {
        expect(screen.getByText("곡-100")).toBeInTheDocument();
      });
      await waitFor(() => {
        expect(sessionMock.state().excludedSongIds).toEqual([100, 200]);
      });

      // 1차 sentinel 진입.
      await act(async () => {
        triggerIntersection();
      });
      await waitFor(() => {
        expect(screen.getByText("곡-300")).toBeInTheDocument();
      });
      expect(createRecommendationMock).toHaveBeenNthCalledWith(2, {
        sessionId: "sess-accum",
        voiceRangeLow: 48,
        voiceRangeHigh: 72,
        excludeSongIds: [100, 200],
      });
      await waitFor(() => {
        expect(sessionMock.state().excludedSongIds).toEqual([100, 200, 300, 400]);
      });

      // 2차 sentinel 진입 — 새 sentinel 노드가 다시 마운트됐는지 대기 후 trigger.
      await waitFor(() => {
        expect(screen.getByTestId("recommend-sentinel")).toBeInTheDocument();
      });
      await act(async () => {
        triggerIntersection();
      });

      await waitFor(() => {
        expect(createRecommendationMock).toHaveBeenCalledTimes(3);
      });
      expect(createRecommendationMock).toHaveBeenNthCalledWith(3, {
        sessionId: "sess-accum",
        voiceRangeLow: 48,
        voiceRangeHigh: 72,
        excludeSongIds: [100, 200, 300, 400],
      });
    });

    it("첫 페이지부터 빈 응답이면 fallback CTA가 노출되고 sentinel은 렌더되지 않는다", async () => {
      sessionMock.set({ sessionId: "sess-empty", voiceRangeId: 33 });
      wireAppendExcluded();

      readVoiceRangeMock.mockResolvedValue({
        id: 33,
        sessionId: "sess-empty",
        lowestNoteMidi: 52,
        highestNoteMidi: 67,
        sourceMethod: "OCTAVE_PICK",
        createdAt: "2026-05-21T00:00:00Z",
        updatedAt: "2026-05-21T00:00:00Z",
      });
      createRecommendationMock.mockResolvedValueOnce({
        requestId: ridFromSeed(999),
        recommendations: [],
      });

      renderWithQueryClient(<RecommendPage />);

      await waitFor(() => {
        expect(
          screen.getByText(
            /더 이상 추천할 곡이 없어요\. 음역대를 다시 입력해 보세요\./,
          ),
        ).toBeInTheDocument();
      });

      expect(
        screen.queryByTestId("recommend-sentinel"),
      ).not.toBeInTheDocument();
      const cta = screen.getAllByRole("link", { name: "음역대 다시 입력" });
      expect(cta.length).toBeGreaterThanOrEqual(1);
    });

    it("두 번째 페이지가 비면 시드 소진 안내가 노출되고 sentinel이 사라진다", async () => {
      sessionMock.set({ sessionId: "sess-exhaust", voiceRangeId: 44 });
      wireAppendExcluded();

      readVoiceRangeMock.mockResolvedValue({
        id: 44,
        sessionId: "sess-exhaust",
        lowestNoteMidi: 50,
        highestNoteMidi: 70,
        sourceMethod: "OCTAVE_PICK",
        createdAt: "2026-05-21T00:00:00Z",
        updatedAt: "2026-05-21T00:00:00Z",
      });

      createRecommendationMock
        .mockResolvedValueOnce(buildResponseWithSongIds(1, [1, 2]))
        .mockResolvedValueOnce({
          requestId: ridFromSeed(2),
          recommendations: [],
        });

      renderWithQueryClient(<RecommendPage />);

      await waitFor(() => {
        expect(screen.getByText("곡-1")).toBeInTheDocument();
      });

      // sentinel 진입 흉내 → 빈 응답으로 hasNextPage=false 가 된다.
      await act(async () => {
        triggerIntersection();
      });

      await waitFor(() => {
        expect(createRecommendationMock).toHaveBeenCalledTimes(2);
      });

      await waitFor(() => {
        expect(
          screen.getByText(/추천할 수 있는 곡을 모두 보여드렸어요\./),
        ).toBeInTheDocument();
      });
      expect(
        screen.queryByTestId("recommend-sentinel"),
      ).not.toBeInTheDocument();
      // 1차 페이지 곡은 여전히 노출.
      expect(screen.getByText("곡-1")).toBeInTheDocument();
    });
  });

  // closes #134 — 추천 응답이 성공하면 히스토리 store 에 push 된다.
  it("추천 응답이 비어있지 않으면 history.appendRecommendation 이 호출된다", async () => {
    sessionMock.set({ sessionId: "sess-hist", voiceRangeId: 77 });

    readVoiceRangeMock.mockResolvedValue({
      id: 77,
      sessionId: "sess-hist",
      lowestNoteMidi: 48,
      highestNoteMidi: 69,
      sourceMethod: "OCTAVE_PICK",
      createdAt: "2026-05-21T00:00:00Z",
      updatedAt: "2026-05-21T00:00:00Z",
    });
    createRecommendationMock.mockResolvedValueOnce(
      buildResponseWithSongIds(555, [555]),
    );

    renderWithQueryClient(<RecommendPage />);

    await waitFor(() => {
      expect(screen.getByText("곡-555")).toBeInTheDocument();
    });

    const append = historyMock.state().appendRecommendation as ReturnType<
      typeof vi.fn
    >;
    await waitFor(() => {
      expect(append).toHaveBeenCalledTimes(1);
    });
    const callArg = append.mock.calls[0][0] as {
      requestId: string;
      voiceRangeId: number | null;
      songs: { song: { id: number } }[];
      excludedSongIds: number[];
    };
    // issue #422: requestId 는 BE UUIDv7 문자열 그대로 전달.
    expect(callArg.requestId).toBe(ridFromSeed(555));
    expect(callArg.voiceRangeId).toBe(77);
    expect(callArg.songs).toHaveLength(1);
    expect(callArg.songs[0].song.id).toBe(555);
  });

  // closes #107 — 추천 결과 페이지는 NoSession fallback, 카드 리스트, 빈 결과 fallback
  // 세 상태가 모두 노출 가능하다. 각 상태에 대해 a11y 위반이 없어야 한다.
  describe("a11y", () => {
    it("NoSession fallback 상태에 a11y 위반이 없다", async () => {
      const { container } = renderWithQueryClient(<RecommendPage />);
      expect(
        screen.getByRole("heading", {
          name: /음역대가 아직 등록되지 않았습니다/,
        }),
      ).toBeInTheDocument();
      await expectNoA11yViolations(container);
    });

    it("카드 리스트 렌더 상태에 a11y 위반이 없다", async () => {
      sessionMock.set({ sessionId: "sess-a11y", voiceRangeId: 1 });
      readVoiceRangeMock.mockResolvedValue({
        id: 1,
        sessionId: "sess-a11y",
        lowestNoteMidi: 48,
        highestNoteMidi: 69,
        sourceMethod: "OCTAVE_PICK",
        createdAt: "2026-05-21T00:00:00Z",
        updatedAt: "2026-05-21T00:00:00Z",
      });
      createRecommendationMock.mockResolvedValue(
        buildResponseWithSongIds(1, [1]),
      );

      const { container } = renderWithQueryClient(<RecommendPage />);

      await waitFor(() => {
        expect(screen.getByText("곡-1")).toBeInTheDocument();
      });

      await expectNoA11yViolations(container);
    });

    // closes #426 — 무한 스크롤로 추천 결과가 추가될 때 스크린 리더 사용자에게
    // 알리는 라이브 영역이 페이지 마운트 직후부터 존재해야 하고, 첫 페이지/추가 페이지
    // 도착 시점에 메시지가 업데이트되어야 한다. 시각적으로는 `sr-only` 로 숨겨지지만
    // DOM 상에는 항상 `role="status"` + `aria-live="polite"` 로 노출된다.
    it("첫 페이지 도착 시 라이브 영역에 '추천 N건을 불러왔습니다.' 메시지가 노출된다 (#426)", async () => {
      sessionMock.set({ sessionId: "sess-live-first", voiceRangeId: 51 });

      readVoiceRangeMock.mockResolvedValue({
        id: 51,
        sessionId: "sess-live-first",
        lowestNoteMidi: 48,
        highestNoteMidi: 69,
        sourceMethod: "OCTAVE_PICK",
        createdAt: "2026-05-23T00:00:00Z",
        updatedAt: "2026-05-23T00:00:00Z",
      });
      createRecommendationMock.mockResolvedValueOnce(
        buildResponseWithSongIds(1, [11, 12, 13]),
      );

      renderWithQueryClient(<RecommendPage />);

      await waitFor(() => {
        expect(screen.getByText("곡-11")).toBeInTheDocument();
      });

      const liveRegion = screen.getByTestId("recommend-live-region");
      expect(liveRegion).toHaveAttribute("role", "status");
      expect(liveRegion).toHaveAttribute("aria-live", "polite");
      expect(liveRegion).toHaveAttribute("aria-atomic", "true");
      await waitFor(() => {
        expect(liveRegion).toHaveTextContent("추천 3건을 불러왔습니다.");
      });
    });

    it("추가 페이지 도착 시 라이브 영역이 '추천 M건이 더 추가되었습니다. (총 N건)' 으로 갱신된다 (#426)", async () => {
      sessionMock.set({ sessionId: "sess-live-next", voiceRangeId: 52 });
      wireAppendExcluded();

      readVoiceRangeMock.mockResolvedValue({
        id: 52,
        sessionId: "sess-live-next",
        lowestNoteMidi: 50,
        highestNoteMidi: 70,
        sourceMethod: "OCTAVE_PICK",
        createdAt: "2026-05-23T00:00:00Z",
        updatedAt: "2026-05-23T00:00:00Z",
      });
      createRecommendationMock
        .mockResolvedValueOnce(buildResponseWithSongIds(1, [21, 22]))
        .mockResolvedValueOnce(buildResponseWithSongIds(2, [31, 32, 33]));

      renderWithQueryClient(<RecommendPage />);

      await waitFor(() => {
        expect(screen.getByText("곡-21")).toBeInTheDocument();
      });

      const liveRegion = screen.getByTestId("recommend-live-region");
      await waitFor(() => {
        expect(liveRegion).toHaveTextContent("추천 2건을 불러왔습니다.");
      });

      // sentinel 진입 → 다음 페이지 페치 → 라이브 영역 메시지 갱신.
      await act(async () => {
        triggerIntersection();
      });

      await waitFor(() => {
        expect(screen.getByText("곡-31")).toBeInTheDocument();
      });
      await waitFor(() => {
        expect(liveRegion).toHaveTextContent(
          "추천 3건이 더 추가되었습니다. (총 5건)",
        );
      });
    });

    it("빈 응답 fallback 상태에 a11y 위반이 없다", async () => {
      sessionMock.set({ sessionId: "sess-empty-a11y", voiceRangeId: 9 });
      readVoiceRangeMock.mockResolvedValue({
        id: 9,
        sessionId: "sess-empty-a11y",
        lowestNoteMidi: 50,
        highestNoteMidi: 70,
        sourceMethod: "OCTAVE_PICK",
        createdAt: "2026-05-21T00:00:00Z",
        updatedAt: "2026-05-21T00:00:00Z",
      });
      createRecommendationMock.mockResolvedValueOnce({
        requestId: ridFromSeed(1),
        recommendations: [],
      });

      const { container } = renderWithQueryClient(<RecommendPage />);

      await waitFor(() => {
        expect(
          screen.getByText(/더 이상 추천할 곡이 없어요/),
        ).toBeInTheDocument();
      });

      await expectNoA11yViolations(container);
    });
  });

  // ---------- closes #282 ----------
  describe("음역대 source method 헤더 뱃지 (#282)", () => {
    it("MIC_MEASURE 응답이면 '마이크 측정' 뱃지와 '마이크로 다시 측정' 링크가 노출된다", async () => {
      const user = userEvent.setup();
      sessionMock.set({ sessionId: "sess-mic", voiceRangeId: 7 });
      readVoiceRangeMock.mockResolvedValue({
        id: 7,
        sessionId: "sess-mic",
        lowestNoteMidi: 50,
        highestNoteMidi: 72,
        sourceMethod: "MIC_MEASURE",
        createdAt: "2026-05-22T00:00:00Z",
        updatedAt: "2026-05-22T00:00:00Z",
      });
      createRecommendationMock.mockResolvedValueOnce(
        buildResponseWithSongIds(1, [1]),
      );

      renderWithQueryClient(<RecommendPage />);

      await waitFor(() => {
        expect(screen.getByText("곡-1")).toBeInTheDocument();
      });

      // 소스 뱃지는 상시 노출. 재측정 링크는 "음역대 자세히" disclosure 안 (#1711).
      const badge = screen.getByTestId("voice-range-source-badge");
      expect(badge).toHaveTextContent("마이크 측정");
      await user.click(screen.getByRole("button", { name: /음역대 자세히/ }));
      expect(
        screen.getByRole("link", { name: "마이크로 다시 측정" }),
      ).toHaveAttribute("href", "/voice-range/auto");
    });

    it("OCTAVE_PICK 응답이면 '직접 선택' 뱃지가 노출되고 '마이크로 다시 측정' 링크는 노출되지 않는다", async () => {
      const user = userEvent.setup();
      sessionMock.set({ sessionId: "sess-pick", voiceRangeId: 8 });
      readVoiceRangeMock.mockResolvedValue({
        id: 8,
        sessionId: "sess-pick",
        lowestNoteMidi: 48,
        highestNoteMidi: 69,
        sourceMethod: "OCTAVE_PICK",
        createdAt: "2026-05-22T00:00:00Z",
        updatedAt: "2026-05-22T00:00:00Z",
      });
      createRecommendationMock.mockResolvedValueOnce(
        buildResponseWithSongIds(1, [1]),
      );

      renderWithQueryClient(<RecommendPage />);

      await waitFor(() => {
        expect(screen.getByText("곡-1")).toBeInTheDocument();
      });

      const badge = screen.getByTestId("voice-range-source-badge");
      expect(badge).toHaveTextContent("직접 선택");
      // 재측정 링크는 "음역대 자세히" disclosure 안 (#1711).
      await user.click(screen.getByRole("button", { name: /음역대 자세히/ }));
      expect(
        screen.queryByRole("link", { name: "마이크로 다시 측정" }),
      ).not.toBeInTheDocument();
      expect(
        screen.getByRole("link", { name: "음역대 다시 입력" }),
      ).toHaveAttribute("href", "/voice-range");
    });

    it("SELF_REPORT 응답이면 '자가 보고' 뱃지가 노출되고 '마이크로 다시 측정' 링크는 노출되지 않는다", async () => {
      const user = userEvent.setup();
      sessionMock.set({ sessionId: "sess-self", voiceRangeId: 9 });
      readVoiceRangeMock.mockResolvedValue({
        id: 9,
        sessionId: "sess-self",
        lowestNoteMidi: 50,
        highestNoteMidi: 70,
        sourceMethod: "SELF_REPORT",
        createdAt: "2026-05-22T00:00:00Z",
        updatedAt: "2026-05-22T00:00:00Z",
      });
      createRecommendationMock.mockResolvedValueOnce(
        buildResponseWithSongIds(1, [1]),
      );

      renderWithQueryClient(<RecommendPage />);

      await waitFor(() => {
        expect(screen.getByText("곡-1")).toBeInTheDocument();
      });

      const badge = screen.getByTestId("voice-range-source-badge");
      expect(badge).toHaveTextContent("자가 보고");
      // 재측정 링크는 "음역대 자세히" disclosure 안 (#1711).
      await user.click(screen.getByRole("button", { name: /음역대 자세히/ }));
      expect(
        screen.queryByRole("link", { name: "마이크로 다시 측정" }),
      ).not.toBeInTheDocument();
    });

    it("헤더 음역대 보조 정보(막대·재입력 링크)는 기본 접힘이고 '음역대 자세히' 토글로 펼친다 (#1711)", async () => {
      const user = userEvent.setup();
      sessionMock.set({ sessionId: "sess-collapse", voiceRangeId: 11 });
      readVoiceRangeMock.mockResolvedValue({
        id: 11,
        sessionId: "sess-collapse",
        lowestNoteMidi: 50,
        highestNoteMidi: 70,
        sourceMethod: "OCTAVE_PICK",
        createdAt: "2026-05-22T00:00:00Z",
        updatedAt: "2026-05-22T00:00:00Z",
      });
      createRecommendationMock.mockResolvedValueOnce(
        buildResponseWithSongIds(1, [1]),
      );

      renderWithQueryClient(<RecommendPage />);

      await waitFor(() => {
        expect(screen.getByText("곡-1")).toBeInTheDocument();
      });

      // 요약(음역대 텍스트)은 상시 노출.
      expect(screen.getByText(/내 음역대:/)).toBeInTheDocument();
      // 접힌 상태: 직관 막대 헤드라인 + 재입력 링크 미노출.
      const toggle = screen.getByRole("button", { name: /음역대 자세히/ });
      expect(toggle).toHaveAttribute("aria-expanded", "false");
      expect(
        screen.queryByTestId("voice-range-relative-headline"),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByRole("link", { name: "음역대 다시 입력" }),
      ).not.toBeInTheDocument();

      // 펼치면 보조 정보가 노출.
      await user.click(toggle);
      expect(toggle).toHaveAttribute("aria-expanded", "true");
      expect(
        screen.getByTestId("voice-range-relative-headline"),
      ).toBeInTheDocument();
      expect(
        screen.getByRole("link", { name: "음역대 다시 입력" }),
      ).toHaveAttribute("href", "/voice-range");
    });
  });
});
