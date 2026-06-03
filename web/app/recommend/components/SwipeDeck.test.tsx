/**
 * SwipeDeck 테스트 (#1489 — 쇼츠식 스와이프 선곡 UX).
 *
 * 검증 범위:
 *  - resolveSwipeIntent 순수 함수 (임계 판정: like / pass / null).
 *  - 첫 카드 + 진행 표시 렌더.
 *  - 좋아요 버튼 → 다음 카드로 전환 + swipeReactions 'like' 기록 + likes store 반영.
 *  - 패스 버튼 → 다음 카드 + swipeReactions 'pass' 기록.
 *  - 덱 소진 시 요약(deck-end) 노출.
 *  - 남은 카드가 임계 이하 + 다음 페이지 존재 → onNeedMore 호출(프리페치).
 *
 * 결정성: matchMedia 를 reduced-motion=true 로 두어 commit 이 즉시 resolve 되게 한다
 * (exit 애니메이션 setTimeout 경로 우회).
 */

import { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import {
  SwipeDeck,
  resolveSwipeIntent,
  computeExitDurationMs,
  SWIPE_EXIT_DURATION_MS,
  SWIPE_EXIT_MIN_DURATION_MS,
} from "./SwipeDeck";
import type { RecommendedSongResponse } from "@/lib/api/recommendation";
import type { UserVoiceRange } from "@/lib/scoreBreakdown";
import { useLikesStore } from "@/store/likes";
import { useSessionStore } from "@/store/session";
import { useSwipeReactionsStore } from "@/store/swipeReactions";

vi.mock("@/lib/api/feedback", () => ({
  toggleLike: vi.fn(),
  toggleBookmark: vi.fn(),
}));

import { toggleLike } from "@/lib/api/feedback";

const toggleLikeMock = vi.mocked(toggleLike);

function renderWithQueryClient(ui: ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const Wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  return render(ui, { wrapper: Wrapper });
}

const USER_RANGE: UserVoiceRange = { lowMidi: 48, highMidi: 72 };

function makeItem(id: number, rank: number): RecommendedSongResponse {
  return {
    song: {
      id,
      title: `곡 ${id}`,
      artist: `가수 ${id}`,
      releaseYear: 2024,
      keyOriginal: "C_MAJOR",
      bpm: 120,
      mood: "UPBEAT",
      language: "ko",
      genre: "POP",
      tjNumber: null,
      kyNumber: null,
      metadataSource: "MANUAL_SEED",
      lowMidi: 50,
      highMidi: 68,
    },
    score: 0.9,
    matchReason: `사유 ${id}`,
    rankPosition: rank,
  };
}

function installReducedMotion(reduce: boolean) {
  Object.defineProperty(window, "matchMedia", {
    writable: true,
    configurable: true,
    value: vi.fn().mockImplementation((query: string) => ({
      matches: query.includes("reduced-motion") ? reduce : false,
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  });
}

beforeEach(() => {
  installReducedMotion(true);
  useLikesStore.setState({ likedSongIds: [] });
  useSwipeReactionsStore.setState({ reactions: [] });
  useSessionStore.setState({
    sessionId: "00000000-0000-4000-8000-000000000001",
    voiceRangeId: null,
    excludedSongIds: [],
  });
  if (typeof localStorage !== "undefined") {
    ["mobruji-likes", "mobruji-session", "mobruji-swipe-reactions"].forEach(
      (k) => localStorage.removeItem(k),
    );
  }
  toggleLikeMock.mockReset();
  toggleLikeMock.mockResolvedValue({ liked: true, songId: 1 });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("resolveSwipeIntent", () => {
  it("우측 임계 초과면 like", () => {
    expect(resolveSwipeIntent(200, 400)).toBe("like");
  });

  it("좌측 임계 초과면 pass", () => {
    expect(resolveSwipeIntent(-200, 400)).toBe("pass");
  });

  it("임계 미만이면 null (스냅백)", () => {
    expect(resolveSwipeIntent(20, 400)).toBeNull();
  });

  it("폭이 작아도 최소 px 임계가 적용된다", () => {
    // 폭 100 → ratio 25px 보다 최소 80px 가 우선.
    expect(resolveSwipeIntent(50, 100)).toBeNull();
    expect(resolveSwipeIntent(90, 100)).toBe("like");
  });

  it("변위는 작아도 같은 방향 빠른 플릭이면 관성으로 커밋한다", () => {
    // 변위 40px(임계 100 미만)이지만 우측으로 빠르게 튕김 → like.
    expect(resolveSwipeIntent(40, 400, 1.2)).toBe("like");
    expect(resolveSwipeIntent(-40, 400, -1.2)).toBe("pass");
  });

  it("속도가 느리거나 변위가 미세하면 플릭으로 보지 않는다", () => {
    // 속도 임계 미만.
    expect(resolveSwipeIntent(40, 400, 0.3)).toBeNull();
    // 변위가 최소 px 미만(미세 떨림).
    expect(resolveSwipeIntent(10, 400, 1.5)).toBeNull();
  });

  it("속도와 변위 방향이 어긋나면 커밋하지 않는다", () => {
    // 오른쪽으로 끌었지만 릴리즈 순간 왼쪽으로 튕김 → 모호 → null.
    expect(resolveSwipeIntent(40, 400, -1.2)).toBeNull();
  });
});

describe("computeExitDurationMs", () => {
  it("느린 릴리즈는 기본 지속을 쓴다", () => {
    expect(computeExitDurationMs(0)).toBe(SWIPE_EXIT_DURATION_MS);
    expect(computeExitDurationMs(0.4)).toBe(SWIPE_EXIT_DURATION_MS);
  });

  it("빠른 플릭일수록 지속이 짧아진다(관성 감속)", () => {
    const slowFlick = computeExitDurationMs(0.8);
    const fastFlick = computeExitDurationMs(2.0);
    expect(fastFlick).toBeLessThan(slowFlick);
    expect(fastFlick).toBeLessThanOrEqual(SWIPE_EXIT_DURATION_MS);
  });

  it("매우 빠른 플릭은 최소 지속으로 수렴한다", () => {
    expect(computeExitDurationMs(5)).toBe(SWIPE_EXIT_MIN_DURATION_MS);
  });
});

describe("SwipeDeck", () => {
  it("첫 카드와 진행 표시를 렌더한다", () => {
    renderWithQueryClient(
      <SwipeDeck
        recommendations={[makeItem(1, 1), makeItem(2, 2)]}
        userVoiceRange={USER_RANGE}
        hasMore={false}
        isFetchingMore={false}
        onNeedMore={vi.fn()}
      />,
    );
    expect(screen.getByText("가수 1")).toBeInTheDocument();
    expect(screen.getByTestId("swipe-progress")).toHaveTextContent("1 / 2");
  });

  it("좋아요 버튼 → 다음 카드 + swipeReactions 'like' 기록 + likes 반영", async () => {
    const user = userEvent.setup();
    renderWithQueryClient(
      <SwipeDeck
        recommendations={[makeItem(1, 1), makeItem(2, 2)]}
        userVoiceRange={USER_RANGE}
        hasMore={false}
        isFetchingMore={false}
        onNeedMore={vi.fn()}
      />,
    );

    await user.click(
      screen.getByRole("button", { name: /곡 1.*좋아요하고 다음 곡/ }),
    );

    // 다음 카드(곡 2)로 전환.
    expect(screen.getByText("가수 2")).toBeInTheDocument();
    // 반응 신호 적재 + 좋아요 store 반영.
    expect(useSwipeReactionsStore.getState().reactionFor(1)).toBe("like");
    expect(useLikesStore.getState().likedSongIds).toContain(1);
  });

  it("패스 버튼 → 다음 카드 + swipeReactions 'pass' 기록", async () => {
    const user = userEvent.setup();
    renderWithQueryClient(
      <SwipeDeck
        recommendations={[makeItem(1, 1), makeItem(2, 2)]}
        userVoiceRange={USER_RANGE}
        hasMore={false}
        isFetchingMore={false}
        onNeedMore={vi.fn()}
      />,
    );

    await user.click(
      screen.getByRole("button", { name: /곡 1.*패스하고 다음 곡/ }),
    );

    expect(screen.getByText("가수 2")).toBeInTheDocument();
    expect(useSwipeReactionsStore.getState().reactionFor(1)).toBe("pass");
    // 패스는 좋아요를 발화하지 않는다.
    expect(useLikesStore.getState().likedSongIds).not.toContain(1);
  });

  it("모든 카드를 넘기면 요약(deck-end)이 노출된다", async () => {
    const user = userEvent.setup();
    renderWithQueryClient(
      <SwipeDeck
        recommendations={[makeItem(1, 1)]}
        userVoiceRange={USER_RANGE}
        hasMore={false}
        isFetchingMore={false}
        onNeedMore={vi.fn()}
      />,
    );

    await user.click(
      screen.getByRole("button", { name: /곡 1.*패스하고 다음 곡/ }),
    );

    expect(screen.getByTestId("swipe-deck-end")).toBeInTheDocument();
    expect(screen.getByText(/좋아요 0곡 · 패스 1곡/)).toBeInTheDocument();
  });

  it("남은 카드가 임계 이하 + 다음 페이지 존재 시 onNeedMore 를 호출한다", () => {
    const onNeedMore = vi.fn();
    renderWithQueryClient(
      <SwipeDeck
        recommendations={[makeItem(1, 1), makeItem(2, 2)]}
        userVoiceRange={USER_RANGE}
        hasMore
        isFetchingMore={false}
        onNeedMore={onNeedMore}
      />,
    );
    // remaining(2) <= PREFETCH_THRESHOLD(2) → 마운트 직후 프리페치.
    expect(onNeedMore).toHaveBeenCalled();
  });
});
