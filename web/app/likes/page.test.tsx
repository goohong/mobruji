/**
 * 좋아한 곡 페이지 테스트 (closes #176 + BE 연동 #184).
 *
 * 시나리오:
 *  - 빈 상태: BE가 빈 배열 응답 시 안내 + CTA 노출, readSongById 호출 없음.
 *  - BE GET이 N건을 반환하면 readSongById가 N번 호출되고 카드 리스트가 렌더된다.
 *  - BE 응답은 zustand store(`useLikesStore`)와 동기화된다.
 */

import { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor } from "@testing-library/react";

import LikesPage from "./page";
import { readLikesBySessionId, type LikeResponse } from "@/lib/api/feedback";
import { readSongById, type SongResponse } from "@/lib/api/song";
import { useLikesStore } from "@/store/likes";
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
const readLikesMock = vi.mocked(readLikesBySessionId);

function buildSong(id: number): SongResponse {
  return {
    id,
    title: `좋아요-곡-${id}`,
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

function buildLike(songId: number): LikeResponse {
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
  readLikesMock.mockReset();
  useLikesStore.setState({ likedSongIds: [] });
  useSessionStore.setState({
    sessionId: "test-session-id",
    voiceRangeId: null,
    excludedSongIds: [],
  });
  if (typeof localStorage !== "undefined") {
    localStorage.removeItem("mobruji-likes");
    localStorage.removeItem("mobruji-session");
  }
});

afterEach(() => {
  cleanup();
});

describe("/likes 페이지", () => {
  it("BE가 빈 배열을 반환하면 빈 상태 CTA를 노출하고 readSongById 호출 없음", async () => {
    readLikesMock.mockResolvedValue([]);

    renderWithQueryClient(<LikesPage />);

    await waitFor(() => {
      expect(
        screen.getByRole("heading", { name: /아직 좋아한 곡이 없어요/ }),
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
    expect(readLikesMock).toHaveBeenCalledWith(
      "test-session-id",
      expect.anything(),
    );
  });

  it("BE GET 응답으로 곡 메타데이터를 페치해서 카드 리스트로 렌더하고 store를 동기화한다", async () => {
    readLikesMock.mockResolvedValue([buildLike(42), buildLike(99)]);
    readSongByIdMock.mockImplementation(async (id) => buildSong(id));

    renderWithQueryClient(<LikesPage />);

    await waitFor(() => {
      expect(screen.getByText("좋아요-곡-42")).toBeInTheDocument();
      expect(screen.getByText("좋아요-곡-99")).toBeInTheDocument();
    });
    expect(readSongByIdMock).toHaveBeenCalledWith(42, expect.anything());
    expect(readSongByIdMock).toHaveBeenCalledWith(99, expect.anything());
    expect(
      screen.getByRole("heading", { name: /좋아한 곡/ }),
    ).toBeInTheDocument();
    // closes #323 — 카드는 페이지 이동이 아닌 상세 모달 트리거 (button + aria-haspopup="dialog").
    const detailTrigger = screen.getByRole("button", {
      name: /좋아요-곡-42 상세 보기/,
    });
    expect(detailTrigger).toHaveAttribute("aria-haspopup", "dialog");
    // zustand store가 BE 응답과 동기화돼야 함.
    expect(useLikesStore.getState().likedSongIds).toEqual([42, 99]);
  });

  // closes #431 — 카운트 영역이 polite 라이브 영역으로 마킹되고 BE 응답 도착 시
  // 메시지가 업데이트되어야 한다. PR #428 /recommend 와 동일 패턴.
  it("BE 응답이 도착하면 라이브 영역에 '총 N곡을 좋아했어요.' 메시지가 노출된다 (#431)", async () => {
    readLikesMock.mockResolvedValue([buildLike(7), buildLike(8)]);
    readSongByIdMock.mockImplementation(async (id) => buildSong(id));

    renderWithQueryClient(<LikesPage />);

    await waitFor(() => {
      expect(screen.getByText("좋아요-곡-7")).toBeInTheDocument();
    });

    const liveRegion = screen.getByTestId("likes-count-live");
    expect(liveRegion).toHaveAttribute("role", "status");
    expect(liveRegion).toHaveAttribute("aria-live", "polite");
    expect(liveRegion).toHaveAttribute("aria-atomic", "true");
    await waitFor(() => {
      expect(liveRegion).toHaveTextContent("총 2곡을 좋아했어요.");
    });
  });
});
