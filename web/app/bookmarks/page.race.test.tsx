/**
 * /bookmarks 페이지 mutation race 가드 (PR #993 후속, PR #1048 패턴 확장).
 *
 * 구조는 `web/app/likes/page.race.test.tsx` 와 대칭 — 두 페이지가 동일한 흐름이라
 * 의도적으로 동일 5 시나리오를 박아 회귀 시 비교 가독성을 확보한다.
 *
 * AS-IS / TO-BE / 비범위 / 보안 — 동일 (`page.race.test.tsx` 의 likes 버전 머리말
 * 참조). 차이점:
 *   - hook: `useBookmarkToggleMutation` / API: `toggleBookmark` / 응답: `bookmarked`.
 *   - store: `useBookmarksStore` (localStorage key `mobruji-bookmarks`).
 *   - 에러 메시지: "북마크 처리에 실패했어요. 다시 시도해 주세요."
 *   - aria-label: "{title} 북마크" / "{title} 북마크 해제".
 *
 * 보안:
 *   - sessionId 는 PII (logging.ts §SENSITIVE_KEYS).
 *   - localStorage 키 `mobruji-bookmarks` 는 songId 만 보관 (PII 없음).
 */

import { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import BookmarksPage from "./page";
import {
  readBookmarksBySessionId,
  type BookmarkListResponse,
  type BookmarkWithSongResponse,
  type BookmarkToggleResponse,
} from "@/lib/api/feedback";
import {
  createDeferred,
  makeQueryClient,
  makeWrapper,
  unauthorizedError,
} from "@/lib/test-helpers/race-helpers";
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

// safeLog mute — unmount 시나리오의 console.error spy 와 noise 분리.
vi.mock("@/lib/logging", () => ({
  safeLog: {
    error: vi.fn(),
    warn: vi.fn(),
    info: vi.fn(),
  },
}));

import { toggleBookmark } from "@/lib/api/feedback";

const readBookmarksMock = vi.mocked(readBookmarksBySessionId);
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

// renderWithQueryClient / createDeferred / unauthorizedError 는
// `@/lib/test-helpers/race-helpers` 단일 소스 (PR #1057 refactor).
function renderWithQueryClient(ui: ReactNode) {
  const client = makeQueryClient();
  return render(ui, { wrapper: makeWrapper(client) });
}

beforeEach(() => {
  readBookmarksMock.mockReset();
  toggleBookmarkMock.mockReset();
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

describe("/bookmarks 페이지 mutation race 가드 (PR #993 후속, PR #1048 패턴 확장)", () => {
  // 시나리오 1 — stale closure / cross contamination
  //
  // 동일 의미: likes 버전 시나리오 1 참조. 두 deferred 응답을 역순으로 도착시켜
  // 각 mutation 의 onSuccess closure 가 자기 songId 만 보정하는지 확인.
  it("시나리오 1: 서로 다른 song 의 mutation 응답이 역순 도착해도 각자 자기 songId 만 보정", async () => {
    const user = userEvent.setup();
    readBookmarksMock.mockResolvedValue(buildWrapper([1, 2]));

    const def1 = createDeferred<BookmarkToggleResponse>();
    const def2 = createDeferred<BookmarkToggleResponse>();
    toggleBookmarkMock
      .mockReturnValueOnce(def1.promise)
      .mockReturnValueOnce(def2.promise);

    renderWithQueryClient(<BookmarksPage />);

    const button1 = await screen.findByRole("button", {
      name: /북마크-곡-1 북마크 해제/,
    });
    const button2 = await screen.findByRole("button", {
      name: /북마크-곡-2 북마크 해제/,
    });

    expect(useBookmarksStore.getState().bookmarkedSongIds).toEqual([1, 2]);

    await user.click(button1);
    await waitFor(() => {
      expect(useBookmarksStore.getState().bookmarkedSongIds).toEqual([2]);
    });
    expect(toggleBookmarkMock).toHaveBeenCalledTimes(1);

    await user.click(button2);
    await waitFor(() => {
      expect(useBookmarksStore.getState().bookmarkedSongIds).toEqual([]);
    });
    expect(toggleBookmarkMock).toHaveBeenCalledTimes(2);

    // 역순 도착 — song #2 먼저.
    await act(async () => {
      def2.resolve({ bookmarked: false, songId: 2 });
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(useBookmarksStore.getState().bookmarkedSongIds).toEqual([]);

    await act(async () => {
      def1.resolve({ bookmarked: false, songId: 1 });
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(useBookmarksStore.getState().bookmarkedSongIds).toEqual([]);
    expect(toggleBookmarkMock).toHaveBeenCalledTimes(2);

    const calls = toggleBookmarkMock.mock.calls;
    const calledSongIds = calls.map(([req]) => req.songId).sort();
    expect(calledSongIds).toEqual([1, 2]);
  });

  // 시나리오 2 — double-click within page
  it("시나리오 2: 같은 song 의 빠른 더블 클릭 → toggleBookmark 1회만 호출 (isPending 가드)", async () => {
    const user = userEvent.setup();
    readBookmarksMock.mockResolvedValue(buildWrapper([1]));

    const def = createDeferred<BookmarkToggleResponse>();
    toggleBookmarkMock.mockReturnValueOnce(def.promise);

    renderWithQueryClient(<BookmarksPage />);

    const button = await screen.findByRole("button", {
      name: /북마크-곡-1 북마크 해제/,
    });

    await user.click(button);
    await waitFor(() => {
      expect(toggleBookmarkMock).toHaveBeenCalledTimes(1);
    });

    await user.click(button).catch(() => {
      // disabled 클릭이 throw 할 수 있음 — 핵심은 BE 호출 0건 추가.
    });
    expect(toggleBookmarkMock).toHaveBeenCalledTimes(1);

    await act(async () => {
      def.resolve({ bookmarked: false, songId: 1 });
      await Promise.resolve();
    });
    expect(toggleBookmarkMock).toHaveBeenCalledTimes(1);
  });

  // 시나리오 3 — 401 → alert + 낙관 롤백 (페이지 컨텍스트)
  it("시나리오 3: 401 응답 → 해당 song 카드에만 alert 노출 + store 롤백 (다른 카드 오염 없음)", async () => {
    const user = userEvent.setup();
    readBookmarksMock.mockResolvedValue(buildWrapper([1, 2]));
    toggleBookmarkMock.mockRejectedValueOnce(unauthorizedError());

    renderWithQueryClient(<BookmarksPage />);

    const button1 = await screen.findByRole("button", {
      name: /북마크-곡-1 북마크 해제/,
    });
    expect(useBookmarksStore.getState().bookmarkedSongIds).toEqual([1, 2]);

    await user.click(button1);

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(/북마크 처리에 실패했어요/);
    expect(alert).toHaveAttribute("aria-live", "assertive");

    expect(screen.getAllByRole("alert")).toHaveLength(1);

    await waitFor(() => {
      expect(useBookmarksStore.getState().bookmarkedSongIds).toContain(1);
      expect(useBookmarksStore.getState().bookmarkedSongIds).toContain(2);
    });
  });

  // 시나리오 4 — unmount mid-mutation (페이지 전체)
  it("시나리오 4: pending 중 페이지 unmount → React state update warning 0건", async () => {
    const user = userEvent.setup();
    readBookmarksMock.mockResolvedValue(buildWrapper([1]));

    const def = createDeferred<BookmarkToggleResponse>();
    toggleBookmarkMock.mockReturnValueOnce(def.promise);

    const consoleErrorSpy = vi
      .spyOn(console, "error")
      .mockImplementation(() => {});

    const { unmount } = renderWithQueryClient(<BookmarksPage />);

    const button = await screen.findByRole("button", {
      name: /북마크-곡-1 북마크 해제/,
    });
    await user.click(button);
    await waitFor(() => {
      expect(toggleBookmarkMock).toHaveBeenCalledTimes(1);
    });

    unmount();

    await act(async () => {
      def.resolve({ bookmarked: false, songId: 1 });
      await Promise.resolve();
      await Promise.resolve();
    });

    const warningCalls = consoleErrorSpy.mock.calls.filter((args) => {
      const message = typeof args[0] === "string" ? args[0] : "";
      return (
        message.includes("unmounted component") ||
        message.includes("memory leak") ||
        message.includes("state update on an unmounted")
      );
    });
    expect(warningCalls).toEqual([]);

    consoleErrorSpy.mockRestore();
  });

  // 시나리오 5 — zustand persist race
  it("시나리오 5: optimistic + BE 실패 → store 롤백 + localStorage `mobruji-bookmarks` 정합", async () => {
    const user = userEvent.setup();
    readBookmarksMock.mockResolvedValue(buildWrapper([1]));
    toggleBookmarkMock.mockRejectedValueOnce(unauthorizedError());

    renderWithQueryClient(<BookmarksPage />);

    const button = await screen.findByRole("button", {
      name: /북마크-곡-1 북마크 해제/,
    });

    expect(useBookmarksStore.getState().bookmarkedSongIds).toEqual([1]);

    await user.click(button);

    await waitFor(() => {
      expect(useBookmarksStore.getState().bookmarkedSongIds).toEqual([1]);
    });

    const persisted = localStorage.getItem("mobruji-bookmarks");
    expect(persisted).not.toBeNull();
    const parsed = JSON.parse(persisted as string) as {
      state: { bookmarkedSongIds: number[] };
    };
    expect(parsed.state.bookmarkedSongIds).toEqual([1]);
  });
});
