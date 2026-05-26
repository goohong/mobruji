/**
 * /bookmarks 페이지 컨텍스트에서 SongCard.LikeButton / SongCard.BookmarkButton 의
 * hook → UI reflect 통합 회귀 가드 (PR #989 후속).
 *
 * AS-IS:
 *   - PR #985 hook 레벨 race/unmount/401 가드 + PR #989 SongCard 직접 마운트
 *     컴포넌트 레벨 16 통합 가드는 있으나, 실제 사용자 흐름인 `/bookmarks` 페이지
 *     → BE GET wrapper 로딩 → SongCard 리스트 마운트 → 토글 시나리오의 page-context
 *     통합 가드는 부재. /likes 페이지에 대한 동일 가드는 `web/app/likes/PageButtonReflect.integration.test.tsx`
 *     가 cover.
 *
 * TO-BE:
 *   본 파일은 `/bookmarks` 페이지 마운트 → SongCard.LikeButton / SongCard.BookmarkButton
 *   2 버튼 × 3 시나리오 = 6 페이지 레벨 통합 가드.
 *     A. pending 상태 → disabled + aria-busy="true" reflect
 *     B. 401 ApiError → 한국어 alert (aria-live=assertive) reflect
 *     C. 정상 toggle → optimistic UI 즉시 update (aria-pressed false→true)
 *
 *   /likes 페이지 가드와 대칭. 두 페이지가 거의 동일한 구조 (BE join wrapper +
 *   zustand store sync) 이므로 page composition 회귀 (예: store 잘못 wiring,
 *   bookmarksQuery key 어긋남) 가 한쪽만 깨지는 케이스를 양 페이지에서 박아 차단.
 *
 * 비범위:
 *   - 페이지 본체 / hook / SongCard 컴포넌트 변경 없음 (테스트만 추가).
 *   - hook 자체 race / unmount / 401 흐름 — PR #985 hook 테스트 cover.
 *   - SongCard / SongDetailContent 직접 마운트 가드 — PR #989 cover.
 *   - auto-dismiss 정확 duration — useAutoDismissMessage.test.ts (lib 레벨) cover.
 *
 * 보안:
 *   - sessionId 는 PII (logging.ts §SENSITIVE_KEYS). "00000000-0000-4000-8000-000000000001" 더미값.
 */

import { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import BookmarksPage from "./page";
import {
  readBookmarksBySessionId,
  type BookmarkListResponse,
  type BookmarkWithSongResponse,
} from "@/lib/api/feedback";
import { ApiError } from "@/lib/api/client";
import type { SongResponse } from "@/lib/api/song";
import { useBookmarksStore } from "@/store/bookmarks";
import { useLikesStore } from "@/store/likes";
import { useSessionStore } from "@/store/session";

vi.mock("@/lib/api/feedback", () => ({
  readLikesBySessionId: vi.fn(),
  readBookmarksBySessionId: vi.fn(),
  toggleLike: vi.fn(),
  toggleBookmark: vi.fn(),
}));

import { toggleBookmark, toggleLike } from "@/lib/api/feedback";

const readBookmarksMock = vi.mocked(readBookmarksBySessionId);
const toggleLikeMock = vi.mocked(toggleLike);
const toggleBookmarkMock = vi.mocked(toggleBookmark);

function buildSong(id: number): SongResponse {
  return {
    id,
    title: `북마크-곡-${id}`,
    artist: `가수-${id}`,
    releaseYear: 2024,
    keyOriginal: "C_MAJOR",
    bpm: 110,
    mood: "UPBEAT",
    language: "ko",
    genre: "POP",
    tjNumber: null,
    kyNumber: null,
    metadataSource: "MANUAL_SEED",
  };
}

function buildBookmarkEntry(songId: number): BookmarkWithSongResponse {
  return {
    id: songId * 10,
    song: buildSong(songId),
    bookmarkedAt: "2026-05-20T12:00:00",
  };
}

function buildWrapper(songIds: number[]): BookmarkListResponse {
  return {
    responses: songIds.map(buildBookmarkEntry),
    page: 0,
    size: 20,
    totalCount: songIds.length,
    hasNext: false,
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
 * 외부 컨트롤 deferred Promise. PR #989 / likes/PageButtonReflect.integration.test.tsx
 * 와 동일 — pending 상태 관찰을 위해 resolve 를 외부 트리거로 분리.
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
  readBookmarksMock.mockReset();
  toggleLikeMock.mockReset();
  toggleBookmarkMock.mockReset();
  // /bookmarks 페이지가 마운트하자마자 GET 을 쏘므로 default 로 단일 곡 wrapper.
  readBookmarksMock.mockResolvedValue(buildWrapper([1]));
  useLikesStore.setState({ likedSongIds: [] });
  useBookmarksStore.setState({ bookmarkedSongIds: [] });
  useSessionStore.setState({
    sessionId: "00000000-0000-4000-8000-000000000001",
    voiceRangeId: null,
    excludedSongIds: [],
  });
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

describe("/bookmarks 페이지 — Button UI reflect 통합 가드 (PR #989 후속)", () => {
  // 시나리오 A — pending 상태 → disabled + aria-busy="true" reflect
  describe("시나리오 A — pending 상태 reflect (disabled + aria-busy)", () => {
    it("LikeButton: 클릭 직후 disabled + aria-busy=true, BE 응답 후 해제", async () => {
      const user = userEvent.setup();
      const def = deferred<{ liked: boolean; songId: number }>();
      toggleLikeMock.mockImplementation(() => def.promise);

      renderWithQueryClient(<BookmarksPage />);

      // /bookmarks 페이지는 likes store 와 무관 — 곡 #1 초기 liked=false.
      const button = await screen.findByRole("button", {
        name: /북마크-곡-1 좋아요$/,
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
          screen.getByRole("button", { name: /북마크-곡-1 좋아요 취소/ }),
        ).not.toBeDisabled();
      });
      expect(
        screen.getByRole("button", { name: /북마크-곡-1 좋아요 취소/ }),
      ).toHaveAttribute("aria-busy", "false");
    });

    it("BookmarkButton: 클릭 직후 disabled + aria-busy=true, BE 응답 후 해제", async () => {
      const user = userEvent.setup();
      const def = deferred<{ bookmarked: boolean; songId: number }>();
      toggleBookmarkMock.mockImplementation(() => def.promise);

      renderWithQueryClient(<BookmarksPage />);

      // /bookmarks 페이지가 곡 #1 을 store 에 push — 초기 bookmarked=true (해제 라벨).
      const button = await screen.findByRole("button", {
        name: /북마크-곡-1 북마크 해제/,
      });
      expect(button).not.toBeDisabled();
      expect(button).toHaveAttribute("aria-busy", "false");

      await user.click(button);

      await waitFor(() => {
        expect(button).toBeDisabled();
      });
      expect(button).toHaveAttribute("aria-busy", "true");

      await act(async () => {
        def.resolve({ bookmarked: false, songId: 1 });
        await Promise.resolve();
      });

      await waitFor(() => {
        expect(
          screen.getByRole("button", { name: /북마크-곡-1 북마크$/ }),
        ).not.toBeDisabled();
      });
      expect(
        screen.getByRole("button", { name: /북마크-곡-1 북마크$/ }),
      ).toHaveAttribute("aria-busy", "false");
    });
  });

  // 시나리오 B — 401 ApiError → 한국어 alert (aria-live=assertive) reflect
  describe("시나리오 B — 401 ApiError 시 alert 노출", () => {
    it("LikeButton: 401 ApiError → 좋아요 한국어 안내 alert + aria-live=assertive", async () => {
      const user = userEvent.setup();
      toggleLikeMock.mockRejectedValueOnce(unauthorizedError());

      renderWithQueryClient(<BookmarksPage />);

      const button = await screen.findByRole("button", {
        name: /북마크-곡-1 좋아요$/,
      });
      await user.click(button);

      const alert = await screen.findByRole("alert");
      expect(alert).toHaveTextContent(/좋아요 처리에 실패했어요/);
      expect(alert).toHaveAttribute("aria-live", "assertive");
    });

    it("BookmarkButton: 401 ApiError → 북마크 한국어 안내 alert + aria-live=assertive", async () => {
      const user = userEvent.setup();
      toggleBookmarkMock.mockRejectedValueOnce(unauthorizedError());

      renderWithQueryClient(<BookmarksPage />);

      const button = await screen.findByRole("button", {
        name: /북마크-곡-1 북마크 해제/,
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

      renderWithQueryClient(<BookmarksPage />);

      const button = await screen.findByRole("button", {
        name: /북마크-곡-1 좋아요$/,
      });
      expect(button).toHaveAttribute("aria-pressed", "false");

      await user.click(button);

      await waitFor(() => {
        expect(
          screen.getByRole("button", { name: /북마크-곡-1 좋아요 취소/ }),
        ).toHaveAttribute("aria-pressed", "true");
      });

      await act(async () => {
        def.resolve({ liked: true, songId: 1 });
        await Promise.resolve();
      });
    });

    it("BookmarkButton: 북마크 해제 클릭 → BE 응답 대기 중에도 aria-pressed=true→false 즉시 reflect", async () => {
      const user = userEvent.setup();
      const def = deferred<{ bookmarked: boolean; songId: number }>();
      toggleBookmarkMock.mockImplementation(() => def.promise);

      renderWithQueryClient(<BookmarksPage />);

      const button = await screen.findByRole("button", {
        name: /북마크-곡-1 북마크 해제/,
      });
      expect(button).toHaveAttribute("aria-pressed", "true");

      await user.click(button);

      await waitFor(() => {
        expect(
          screen.getByRole("button", { name: /북마크-곡-1 북마크$/ }),
        ).toHaveAttribute("aria-pressed", "false");
      });

      await act(async () => {
        def.resolve({ bookmarked: false, songId: 1 });
        await Promise.resolve();
      });
    });
  });
});
