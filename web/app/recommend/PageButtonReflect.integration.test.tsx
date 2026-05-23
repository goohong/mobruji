/**
 * /recommend 페이지 컨텍스트에서 SongCard.LikeButton / SongCard.BookmarkButton 의
 * hook → UI reflect 통합 회귀 가드 (PR #993 후속).
 *
 * AS-IS:
 *   - PR #985 hook 레벨 race/unmount/401 가드 + PR #989 SongCard 직접 마운트
 *     16 통합 가드 + PR #993 `/likes` `/bookmarks` 페이지 컨텍스트 12 가드는 있으나,
 *     실제 PoC 핵심 흐름인 `/recommend` 페이지 (voice-range 조회 → 무한 스크롤 추천
 *     리스트 → SongCard.LikeButton/BookmarkButton 토글) 페이지 컨텍스트 가드는 부재.
 *   - `/recommend` 는 다른 두 페이지와 결정적인 차이: SongCard 가 `item`
 *     (`RecommendedSongResponse`) 모드 + `onShowDetail` 모달 모드로 마운트된다.
 *     이 분기는 SongCard 본문에서 `matchReason` / `score` 노출을 모달로 위임시키고
 *     (`isModalMode = true`) 카드 표면엔 button 만 남긴다 — 그래서 페이지 레벨
 *     회귀 시 "본문 truncate matchReason 누락" / "score chip 누락" 같은 변화가
 *     button reflect 와 마스킹 충돌을 일으킬 수 있다. 본 파일은 그 분기 위에서
 *     button reflect 가 변함없이 동작하는지를 페이지 마운트로 박는다.
 *
 * TO-BE:
 *   본 파일은 `/recommend` 페이지 마운트 → SongCard (item + onShowDetail 모드)
 *   2 버튼 × 3 시나리오 = 6 페이지 레벨 통합 가드. matchReason/score 분기가
 *   카드 표면에서 사라진 상태에서도 button reflect 가 정상 작동함을 함께 검증.
 *     A. pending 상태 → disabled + aria-busy="true" reflect (2건)
 *     B. 401 ApiError → 한국어 alert (aria-live=assertive) reflect (2건)
 *     C. 정상 toggle → optimistic UI 즉시 update (aria-pressed false→true) (2건)
 *
 *   `/likes` `/bookmarks` 가드 (PR #993) 와 대칭. 두 페이지가 `song` 모드 + non-모달이라
 *   matchReason/score 노출 분기가 다르고, recommend 가 `item` 모드 + 모달이라 분기가
 *   완전히 갈린다. 페이지 composition 회귀 (예: page 가 onShowDetail 을 빼서 modal mode
 *   가 깨지거나, useInfiniteQuery 결과 평탄화가 SongCard prop 시그니처에 mismatch
 *   되면) 가 컴포넌트 단독 테스트로 누락되는 시나리오를 페이지 마운트로 차단.
 *
 * 비범위:
 *   - 페이지 본체 / hook / SongCard 컴포넌트 변경 없음 (테스트만 추가).
 *   - hook 자체 race / unmount / 401 흐름 — PR #985 hook 테스트 cover.
 *   - SongCard / SongDetailContent 직접 마운트 가드 — PR #989 cover.
 *   - `/likes` `/bookmarks` 페이지 가드 — PR #993 cover.
 *   - auto-dismiss 정확 duration — useAutoDismissMessage.test.ts (lib 레벨) cover.
 *   - 무한 스크롤 sentinel / history.appendRecommendation 등 페이지 본체 동작 —
 *     기존 `page.test.tsx` 가 cover. 본 파일은 button reflect 만 의도적 좁은 범위.
 *   - test-utils 추출 — 본 사이클 비범위. 동일 패턴 3 파일 (PR #993 + 본 PR) 중복은
 *     추후 별 사이클 검토.
 *
 * 보안:
 *   - sessionId 는 PII (logging.ts §SENSITIVE_KEYS). "test-session-id" 더미값 사용.
 */

import { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import RecommendPage from "./page";
import { ApiError } from "@/lib/api/client";
import { createRecommendation } from "@/lib/api/recommendation";
import { readVoiceRange } from "@/lib/api/voice-range";
import { useBookmarksStore } from "@/store/bookmarks";
import { useLikesStore } from "@/store/likes";
import { useSessionStore } from "@/store/session";

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

vi.mock("@/lib/api/feedback", () => ({
  readLikesBySessionId: vi.fn(),
  readBookmarksBySessionId: vi.fn(),
  toggleLike: vi.fn(),
  toggleBookmark: vi.fn(),
}));

import { toggleBookmark, toggleLike } from "@/lib/api/feedback";

const readVoiceRangeMock = vi.mocked(readVoiceRange);
const createRecommendationMock = vi.mocked(createRecommendation);
const toggleLikeMock = vi.mocked(toggleLike);
const toggleBookmarkMock = vi.mocked(toggleBookmark);

const SESSION_ID = "test-session-id";
const VOICE_RANGE_ID = 42;

/**
 * 추천 응답 헬퍼.
 *
 * matchReason / score 분기 cover 의도:
 *   - 단일 곡 응답 + `matchReason` 명시 + `score` 명시. SongCard 가 `item` 모드 +
 *     `onShowDetail` 모달 모드로 마운트되면 `isModalMode=true` 가 되어 카드 표면에
 *     matchReason / score 가 노출되지 않는다. 본 분기를 통과한 후에도 LikeButton /
 *     BookmarkButton 의 reflect 가 정상 작동함을 페이지 마운트로 검증.
 *   - 회귀 시나리오 예: page 가 `onShowDetail` 을 빼면 `isModalMode=false` 가 되어
 *     matchReason / score 가 표면에 노출되는데, 본 테스트가 SongCard 의 ARIA pressed/
 *     busy 만 보고 있어서 그 회귀를 직접 잡지는 않지만, 본문 노출 회귀가 button DOM
 *     query 의 unique 매칭을 깨뜨리는 부수 케이스를 함께 차단한다.
 */
function buildSong(id: number) {
  return {
    id,
    title: `추천-곡-${id}`,
    artist: `가수-${id}`,
    releaseYear: 2024,
    keyOriginal: "C_MAJOR" as const,
    bpm: 110,
    mood: "UPBEAT" as const,
    language: "ko",
    genre: "POP",
    tjNumber: `T-${id}`,
    kyNumber: `K-${id}`,
    metadataSource: "MANUAL_SEED" as const,
  };
}

function ridFromSeed(seed: number): string {
  return `01933b1c-7f8a-7c2d-9b3e-${seed.toString(16).padStart(12, "0")}`;
}

function buildRecommendationResponse(seed: number, songIds: number[]) {
  return {
    requestId: ridFromSeed(seed),
    recommendations: songIds.map((id, idx) => ({
      rankPosition: idx + 1,
      // matchReason / score 분기 cover — 본문엔 노출되지 않지만 prop 시그니처 정합성 확인.
      score: 0.9 - idx * 0.05,
      matchReason: "음역 매칭",
      song: buildSong(id),
    })),
  };
}

function defaultVoiceRange() {
  return {
    id: VOICE_RANGE_ID,
    sessionId: SESSION_ID,
    lowestNoteMidi: 48,
    highestNoteMidi: 69,
    sourceMethod: "OCTAVE_PICK" as const,
    createdAt: "2026-05-21T00:00:00Z",
    updatedAt: "2026-05-21T00:00:00Z",
  };
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
 * 외부 컨트롤 deferred Promise — PR #989 / #993 동일 패턴. pending 상태 관찰 위해
 * resolve 를 외부 트리거로 분리.
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

function unauthorizedError(): ApiError {
  return new ApiError(401, "Unauthorized", { error: "UNAUTHORIZED" });
}

beforeEach(() => {
  readVoiceRangeMock.mockReset();
  createRecommendationMock.mockReset();
  toggleLikeMock.mockReset();
  toggleBookmarkMock.mockReset();
  // /recommend 마운트 → voice-range 조회 → 첫 페이지 추천 응답. 단일 곡으로 시작해서
  // button DOM query 가 곡 #1 에 unique 매칭되게 둔다.
  readVoiceRangeMock.mockResolvedValue(defaultVoiceRange());
  createRecommendationMock.mockResolvedValue(
    buildRecommendationResponse(100, [1]),
  );
  // session 초기화 — sessionId 만 있고 like/bookmark store 는 비워서 초기 상태
  // (liked=false, bookmarked=false) 가 결정적으로 보장된다.
  useSessionStore.setState({
    sessionId: SESSION_ID,
    voiceRangeId: VOICE_RANGE_ID,
    excludedSongIds: [],
  });
  useLikesStore.setState({ likedSongIds: [] });
  useBookmarksStore.setState({ bookmarkedSongIds: [] });
  if (typeof localStorage !== "undefined") {
    ["mobruji-likes", "mobruji-bookmarks", "mobruji-session"].forEach((k) =>
      localStorage.removeItem(k),
    );
  }
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe("/recommend 페이지 — Button UI reflect 통합 가드 (PR #993 후속)", () => {
  // 시나리오 A — pending 상태 → disabled + aria-busy="true" reflect
  describe("시나리오 A — pending 상태 reflect (disabled + aria-busy)", () => {
    it("LikeButton: 클릭 직후 disabled + aria-busy=true, BE 응답 후 해제", async () => {
      const user = userEvent.setup();
      const def = deferred<{ liked: boolean; songId: number }>();
      toggleLikeMock.mockImplementation(() => def.promise);

      renderWithQueryClient(<RecommendPage />);

      // 추천 첫 페이지 로딩 완료까지 대기. 초기 liked=false → "좋아요" 라벨.
      const button = await screen.findByRole("button", {
        name: /추천-곡-1 좋아요$/,
      });
      expect(button).not.toBeDisabled();
      expect(button).toHaveAttribute("aria-busy", "false");

      await user.click(button);

      await waitFor(() => {
        expect(button).toBeDisabled();
      });
      expect(button).toHaveAttribute("aria-busy", "true");

      await act(async () => {
        def.resolve({ liked: true, songId: 1 });
        await Promise.resolve();
      });

      await waitFor(() => {
        expect(
          screen.getByRole("button", { name: /추천-곡-1 좋아요 취소/ }),
        ).not.toBeDisabled();
      });
      expect(
        screen.getByRole("button", { name: /추천-곡-1 좋아요 취소/ }),
      ).toHaveAttribute("aria-busy", "false");
    });

    it("BookmarkButton: 클릭 직후 disabled + aria-busy=true, BE 응답 후 해제", async () => {
      const user = userEvent.setup();
      const def = deferred<{ bookmarked: boolean; songId: number }>();
      toggleBookmarkMock.mockImplementation(() => def.promise);

      renderWithQueryClient(<RecommendPage />);

      // 초기 bookmarked=false → "북마크" 라벨.
      const button = await screen.findByRole("button", {
        name: /추천-곡-1 북마크$/,
      });
      expect(button).not.toBeDisabled();
      expect(button).toHaveAttribute("aria-busy", "false");

      await user.click(button);

      await waitFor(() => {
        expect(button).toBeDisabled();
      });
      expect(button).toHaveAttribute("aria-busy", "true");

      await act(async () => {
        def.resolve({ bookmarked: true, songId: 1 });
        await Promise.resolve();
      });

      await waitFor(() => {
        expect(
          screen.getByRole("button", { name: /추천-곡-1 북마크 해제/ }),
        ).not.toBeDisabled();
      });
      expect(
        screen.getByRole("button", { name: /추천-곡-1 북마크 해제/ }),
      ).toHaveAttribute("aria-busy", "false");
    });
  });

  // 시나리오 B — 401 ApiError → 한국어 alert (aria-live=assertive) reflect
  describe("시나리오 B — 401 ApiError 시 alert 노출", () => {
    it("LikeButton: 401 ApiError → 좋아요 한국어 안내 alert + aria-live=assertive", async () => {
      const user = userEvent.setup();
      toggleLikeMock.mockRejectedValueOnce(unauthorizedError());

      renderWithQueryClient(<RecommendPage />);

      const button = await screen.findByRole("button", {
        name: /추천-곡-1 좋아요$/,
      });
      await user.click(button);

      const alert = await screen.findByRole("alert");
      expect(alert).toHaveTextContent(/좋아요 처리에 실패했어요/);
      expect(alert).toHaveAttribute("aria-live", "assertive");
    });

    it("BookmarkButton: 401 ApiError → 북마크 한국어 안내 alert + aria-live=assertive", async () => {
      const user = userEvent.setup();
      toggleBookmarkMock.mockRejectedValueOnce(unauthorizedError());

      renderWithQueryClient(<RecommendPage />);

      const button = await screen.findByRole("button", {
        name: /추천-곡-1 북마크$/,
      });
      await user.click(button);

      const alert = await screen.findByRole("alert");
      expect(alert).toHaveTextContent(/북마크 처리에 실패했어요/);
      expect(alert).toHaveAttribute("aria-live", "assertive");
    });
  });

  // 시나리오 C — 정상 toggle → optimistic UI 즉시 update (aria-pressed)
  describe("시나리오 C — 정상 toggle 낙관 UI reflect (aria-pressed)", () => {
    it("LikeButton: 좋아요 클릭 → BE 응답 대기 중에도 aria-pressed=false→true 즉시 reflect", async () => {
      const user = userEvent.setup();
      const def = deferred<{ liked: boolean; songId: number }>();
      toggleLikeMock.mockImplementation(() => def.promise);

      renderWithQueryClient(<RecommendPage />);

      const button = await screen.findByRole("button", {
        name: /추천-곡-1 좋아요$/,
      });
      expect(button).toHaveAttribute("aria-pressed", "false");

      await user.click(button);

      // BE 응답 도착 전에도 zustand store 가 낙관 토글되어 aria-pressed=true 반영.
      await waitFor(() => {
        expect(
          screen.getByRole("button", { name: /추천-곡-1 좋아요 취소/ }),
        ).toHaveAttribute("aria-pressed", "true");
      });

      await act(async () => {
        def.resolve({ liked: true, songId: 1 });
        await Promise.resolve();
      });
    });

    it("BookmarkButton: 북마크 클릭 → BE 응답 대기 중에도 aria-pressed=false→true 즉시 reflect", async () => {
      const user = userEvent.setup();
      const def = deferred<{ bookmarked: boolean; songId: number }>();
      toggleBookmarkMock.mockImplementation(() => def.promise);

      renderWithQueryClient(<RecommendPage />);

      const button = await screen.findByRole("button", {
        name: /추천-곡-1 북마크$/,
      });
      expect(button).toHaveAttribute("aria-pressed", "false");

      await user.click(button);

      await waitFor(() => {
        expect(
          screen.getByRole("button", { name: /추천-곡-1 북마크 해제/ }),
        ).toHaveAttribute("aria-pressed", "true");
      });

      await act(async () => {
        def.resolve({ bookmarked: true, songId: 1 });
        await Promise.resolve();
      });
    });
  });
});
