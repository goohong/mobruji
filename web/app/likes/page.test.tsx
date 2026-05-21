/**
 * 좋아한 곡 페이지 테스트 (closes #176, spec PR D 일부).
 *
 * 시나리오:
 *  - 빈 상태: 좋아요가 0건일 때 안내 + CTA 노출, readSongById 호출 없음.
 *  - 좋아요가 있으면 readSongById 가 N번 호출되고 카드 리스트가 렌더된다.
 */

import { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor } from "@testing-library/react";

import LikesPage from "./page";
import { readSongById, type SongResponse } from "@/lib/api/song";
import { useLikesStore } from "@/store/likes";

vi.mock("@/lib/api/song", async () => {
  const actual =
    await vi.importActual<typeof import("@/lib/api/song")>("@/lib/api/song");
  return {
    ...actual,
    readSongById: vi.fn(),
  };
});

const readSongByIdMock = vi.mocked(readSongById);

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
  useLikesStore.setState({ likedSongIds: [] });
  if (typeof localStorage !== "undefined") {
    localStorage.removeItem("mobruji-likes");
  }
});

afterEach(() => {
  cleanup();
});

describe("/likes 페이지", () => {
  it("좋아요가 0건이면 빈 상태 CTA를 노출하고 readSongById 호출 없음", () => {
    renderWithQueryClient(<LikesPage />);

    expect(
      screen.getByRole("heading", { name: /아직 좋아한 곡이 없어요/ }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: /추천 받으러 가기/ }),
    ).toHaveAttribute("href", "/recommend");
    expect(
      screen.getByRole("link", { name: /곡 검색하기/ }),
    ).toHaveAttribute("href", "/songs");
    expect(readSongByIdMock).not.toHaveBeenCalled();
  });

  it("좋아요한 곡 메타데이터를 페치해서 카드 리스트로 렌더한다", async () => {
    useLikesStore.setState({ likedSongIds: [42, 99] });
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
    // 카드 자체가 곡 상세 링크.
    expect(
      screen.getByRole("link", { name: /좋아요-곡-42 상세 보기/ }),
    ).toHaveAttribute("href", "/songs/42");
  });
});
