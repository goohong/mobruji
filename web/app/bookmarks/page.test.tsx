/**
 * 북마크한 곡 페이지 테스트 (closes #184 + 진입 동선 #252).
 *
 * 시나리오:
 *  - 빈 상태: BE가 빈 배열 응답 시 안내 + CTA 노출, readSongById 호출 없음.
 *  - BE GET이 N건을 반환하면 readSongById가 N번 호출되고 카드 리스트가 렌더된다.
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
  type BookmarkResponse,
} from "@/lib/api/feedback";
import { readSongById, type SongResponse } from "@/lib/api/song";
import { useBookmarksStore } from "@/store/bookmarks";
import { useSessionStore } from "@/store/session";

vi.mock("@/lib/api/song", async () => {
  const actual =
    await vi.importActual<typeof import("@/lib/api/song")>("@/lib/api/song");
  return {
    ...actual,
    readSongById: vi.fn(),
  };
});

vi.mock("@/lib/api/feedback", () => ({
  readLikesBySessionId: vi.fn(),
  toggleLike: vi.fn(),
  toggleBookmark: vi.fn(),
  readBookmarksBySessionId: vi.fn(),
}));

const readSongByIdMock = vi.mocked(readSongById);
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

function buildBookmark(songId: number): BookmarkResponse {
  return {
    id: songId * 10,
    sessionId: "test-session-id",
    songId,
    createdAt: "2026-05-20T12:00:00",
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
  readSongByIdMock.mockReset();
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
  it("BE가 빈 배열을 반환하면 빈 상태 CTA를 노출하고 readSongById 호출 없음", async () => {
    readBookmarksMock.mockResolvedValue([]);

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
    expect(readSongByIdMock).not.toHaveBeenCalled();
    // BE는 sessionId를 받아 호출됐다.
    expect(readBookmarksMock).toHaveBeenCalledWith(
      "test-session-id",
      expect.anything(),
    );
  });

  it("BE GET 응답으로 곡 메타데이터를 페치해서 카드 리스트로 렌더하고 store를 동기화한다", async () => {
    readBookmarksMock.mockResolvedValue([buildBookmark(42), buildBookmark(99)]);
    readSongByIdMock.mockImplementation(async (id) => buildSong(id));

    renderWithQueryClient(<BookmarksPage />);

    await waitFor(() => {
      expect(screen.getByText("북마크-곡-42")).toBeInTheDocument();
      expect(screen.getByText("북마크-곡-99")).toBeInTheDocument();
    });
    expect(readSongByIdMock).toHaveBeenCalledWith(42, expect.anything());
    expect(readSongByIdMock).toHaveBeenCalledWith(99, expect.anything());
    expect(
      screen.getByRole("heading", { name: /북마크한 곡/ }),
    ).toBeInTheDocument();
    // 카드 자체가 곡 상세 링크.
    expect(
      screen.getByRole("link", { name: /북마크-곡-42 상세 보기/ }),
    ).toHaveAttribute("href", "/songs/42");
    // zustand store가 BE 응답과 동기화돼야 함.
    expect(useBookmarksStore.getState().bookmarkedSongIds).toEqual([42, 99]);
  });
});
