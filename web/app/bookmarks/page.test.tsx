/**
 * 북마크한 곡 페이지 테스트 (closes #184 + 진입 동선 #252 + fix #845).
 *
 * 시나리오:
 *  - 빈 상태: BE가 빈 wrapper 응답 시 안내 + CTA 노출.
 *  - BE GET이 N건을 반환하면 응답에 포함된 곡 메타가 카드 리스트로 렌더된다
 *    (closes #845 — BE join 응답이라 별도 readSongById 호출 없음).
 *  - BE 응답은 zustand store(`useBookmarksStore`)와 동기화된다.
 *
 * 구조는 `web/app/likes/page.test.tsx` 와 대칭 — 두 페이지가 동일한 흐름이라
 * 의도적으로 동일 패턴을 유지해 회귀 시 비교 가독성을 확보한다.
 */

import { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor } from "@testing-library/react";

import BookmarksPage from "./page";
import {
  readBookmarksBySessionId,
  type BookmarkListResponse,
  type BookmarkWithSongResponse,
} from "@/lib/api/feedback";
import type { SongResponse } from "@/lib/api/song";
import { useBookmarksStore } from "@/store/bookmarks";
import { useSessionStore } from "@/store/session";

vi.mock("@/lib/api/feedback", () => ({
  readLikesBySessionId: vi.fn(),
  toggleLike: vi.fn(),
  toggleBookmark: vi.fn(),
  readBookmarksBySessionId: vi.fn(),
}));

const readBookmarksMock = vi.mocked(readBookmarksBySessionId);

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
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  }
  return render(ui, { wrapper: Wrapper });
}

beforeEach(() => {
  readBookmarksMock.mockReset();
  useBookmarksStore.setState({ bookmarkedSongIds: [] });
  useSessionStore.setState({
    sessionId: "test-session-id",
    voiceRangeId: null,
    excludedSongIds: [],
  });
  if (typeof localStorage !== "undefined") {
    localStorage.removeItem("mobruji-bookmarks");
    localStorage.removeItem("mobruji-session");
  }
});

afterEach(() => {
  cleanup();
});

describe("/bookmarks 페이지", () => {
  it("BE가 빈 wrapper 를 반환하면 빈 상태 CTA를 노출", async () => {
    readBookmarksMock.mockResolvedValue(buildWrapper([]));

    renderWithQueryClient(<BookmarksPage />);

    await waitFor(() => {
      expect(
        screen.getByRole("heading", { name: /아직 북마크한 곡이 없어요/ }),
      ).toBeInTheDocument();
    });
    expect(
      screen.getByRole("link", { name: /추천 받으러 가기/ }),
    ).toHaveAttribute("href", "/recommend");
    expect(
      screen.getByRole("link", { name: /곡 검색하기/ }),
    ).toHaveAttribute("href", "/songs");
    // BE는 sessionId를 받아 호출됐다.
    expect(readBookmarksMock).toHaveBeenCalledWith(
      "test-session-id",
      expect.anything(),
    );
  });

  it("BE GET 응답(곡 메타 join)을 카드 리스트로 렌더하고 store 를 동기화한다", async () => {
    readBookmarksMock.mockResolvedValue(buildWrapper([42, 99]));

    renderWithQueryClient(<BookmarksPage />);

    await waitFor(() => {
      expect(screen.getByText("북마크-곡-42")).toBeInTheDocument();
      expect(screen.getByText("북마크-곡-99")).toBeInTheDocument();
    });
    expect(
      screen.getByRole("heading", { name: /북마크한 곡/ }),
    ).toBeInTheDocument();
    // closes #323 — 카드는 페이지 이동이 아닌 상세 모달 트리거.
    const detailTrigger = screen.getByRole("button", {
      name: /북마크-곡-42 상세 보기/,
    });
    expect(detailTrigger).toHaveAttribute("aria-haspopup", "dialog");
    // zustand store가 BE 응답과 동기화돼야 함.
    expect(useBookmarksStore.getState().bookmarkedSongIds).toEqual([42, 99]);
  });

  // closes #431 — 카운트 영역이 polite 라이브 영역으로 마킹되고 BE 응답 도착 시
  // 메시지가 업데이트되어야 한다. /likes 와 대칭 패턴.
  it("BE 응답이 도착하면 라이브 영역에 '총 N곡을 북마크했어요.' 메시지가 노출된다 (#431)", async () => {
    readBookmarksMock.mockResolvedValue(buildWrapper([7, 8]));

    renderWithQueryClient(<BookmarksPage />);

    await waitFor(() => {
      expect(screen.getByText("북마크-곡-7")).toBeInTheDocument();
    });

    const liveRegion = screen.getByTestId("bookmarks-count-live");
    expect(liveRegion).toHaveAttribute("role", "status");
    expect(liveRegion).toHaveAttribute("aria-live", "polite");
    expect(liveRegion).toHaveAttribute("aria-atomic", "true");
    await waitFor(() => {
      expect(liveRegion).toHaveTextContent("총 2곡을 북마크했어요.");
    });
  });
});
