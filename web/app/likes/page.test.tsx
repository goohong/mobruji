/**
 * 좋아한 곡 페이지 테스트 (closes #176 + BE 연동 #184 + fix #845).
 *
 * 시나리오:
 *  - 빈 상태: BE가 빈 wrapper 응답 시 안내 + CTA 노출.
 *  - BE GET이 N건을 반환하면 응답에 포함된 곡 메타가 카드 리스트로 렌더된다
 *    (closes #845 — BE join 응답이라 별도 readSongById 호출 없음).
 *  - BE 응답은 zustand store(`useLikesStore`)와 동기화된다.
 */

import { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import LikesPage from "./page";
import {
  readLikesBySessionId,
  type LikeListResponse,
  type LikeWithSongResponse,
} from "@/lib/api/feedback";
import type { SongResponse } from "@/lib/api/song";
import { useLikesStore } from "@/store/likes";
import { useSessionStore } from "@/store/session";

vi.mock("@/lib/api/feedback", () => ({
  readLikesBySessionId: vi.fn(),
  toggleLike: vi.fn(),
  toggleBookmark: vi.fn(),
  readBookmarksBySessionId: vi.fn(),
}));

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

function buildLikeEntry(songId: number): LikeWithSongResponse {
  return {
    id: songId * 10,
    song: buildSong(songId),
    likedAt: "2026-05-20T12:00:00",
  };
}

function buildWrapper(songIds: number[]): LikeListResponse {
  return {
    responses: songIds.map(buildLikeEntry),
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
  readLikesMock.mockReset();
  useLikesStore.setState({ likedSongIds: [] });
  useSessionStore.setState({
    sessionId: "00000000-0000-4000-8000-000000000001",
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
  it("BE가 빈 wrapper 를 반환하면 빈 상태 CTA를 노출", async () => {
    readLikesMock.mockResolvedValue(buildWrapper([]));

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
    // BE는 sessionId를 받아 호출됐다.
    expect(readLikesMock).toHaveBeenCalledWith(
      "00000000-0000-4000-8000-000000000001",
      expect.anything(),
    );
  });

  it("BE GET 응답(곡 메타 join)을 카드 리스트로 렌더하고 store 를 동기화한다", async () => {
    readLikesMock.mockResolvedValue(buildWrapper([42, 99]));

    renderWithQueryClient(<LikesPage />);

    await waitFor(() => {
      expect(screen.getByText("좋아요-곡-42")).toBeInTheDocument();
      expect(screen.getByText("좋아요-곡-99")).toBeInTheDocument();
    });
    expect(
      screen.getByRole("heading", { name: /좋아한 곡/ }),
    ).toBeInTheDocument();
    // closes #323 — 카드는 페이지 이동이 아닌 상세 모달 트리거.
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
    readLikesMock.mockResolvedValue(buildWrapper([7, 8]));

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

  // 이슈 #1284 — 한국 곡은 한국어 표기 우선. 상세 모달 제목이 formatSongDisplayTitle 을
  // 거쳐 "English (한글)" dual-name 을 한글 우선으로 swap 하는지 회귀 검증.
  it("한국 곡 상세 모달 제목이 한국어 우선으로 노출된다 (#1284)", async () => {
    const koreanSong: SongResponse = {
      ...buildSong(42),
      title: "Spring Day (봄날)",
      language: "ko",
    };
    readLikesMock.mockResolvedValue({
      responses: [{ id: 420, song: koreanSong, likedAt: "2026-05-20T12:00:00" }],
      page: 0,
      size: 20,
      totalCount: 1,
      hasNext: false,
    });

    const user = userEvent.setup();
    renderWithQueryClient(<LikesPage />);

    const trigger = await screen.findByRole("button", {
      name: /봄날 \(Spring Day\) 상세 보기/,
    });
    await user.click(trigger);

    const dialog = await screen.findByRole("dialog");
    expect(
      within(dialog).getByRole("heading", { name: "봄날 (Spring Day)" }),
    ).toBeInTheDocument();
  });

  // 비-한국 곡 회귀 — language 가 한국어가 아니면 원본 표기를 그대로 유지.
  it("비-한국 곡 상세 모달 제목은 원본 그대로 노출된다 (#1284)", async () => {
    const englishSong: SongResponse = {
      ...buildSong(7),
      title: "Bohemian Rhapsody",
      language: "en",
    };
    readLikesMock.mockResolvedValue({
      responses: [{ id: 70, song: englishSong, likedAt: "2026-05-20T12:00:00" }],
      page: 0,
      size: 20,
      totalCount: 1,
      hasNext: false,
    });

    const user = userEvent.setup();
    renderWithQueryClient(<LikesPage />);

    const trigger = await screen.findByRole("button", {
      name: /Bohemian Rhapsody 상세 보기/,
    });
    await user.click(trigger);

    const dialog = await screen.findByRole("dialog");
    expect(
      within(dialog).getByRole("heading", { name: "Bohemian Rhapsody" }),
    ).toBeInTheDocument();
  });
});
