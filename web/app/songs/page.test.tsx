/**
 * 곡 검색 페이지 테스트.
 *
 * 시나리오:
 *  - 초기: 검색어가 비어 있어 "검색어를 입력해 보세요" 가이드만 노출, API 호출 없음.
 *  - 검색어 입력 → 300ms 디바운스 → searchSongs 호출 → 결과 카드 리스트.
 *  - 응답이 빈 배열이면 "검색 결과 없음" fallback.
 *  - 난이도 필터로 클라이언트 사이드 필터링.
 *
 * 구현 노트:
 *  - fake timer는 userEvent / React Query / waitFor와 결합 시 일관된 결과를 내기 어렵다.
 *    실제 setTimeout(300ms)을 그대로 흘러가게 두고, waitFor 타임아웃만 충분히(2초)
 *    잡아 디바운스 윈도우를 자연스럽게 통과한다. 300ms는 테스트 총 시간에 미미하다.
 */

import { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import SongSearchPage from "./page";
import { searchSongs } from "@/lib/api/song";

vi.mock("@/lib/api/song", async () => {
  const actual =
    await vi.importActual<typeof import("@/lib/api/song")>("@/lib/api/song");
  return {
    ...actual,
    searchSongs: vi.fn(),
  };
});

const searchSongsMock = vi.mocked(searchSongs);

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
  searchSongsMock.mockReset();
});

afterEach(() => {
  cleanup();
});

describe("SongSearchPage", () => {
  it("초기 상태에서는 검색어 가이드를 노출하고 API를 호출하지 않는다", () => {
    renderWithQueryClient(<SongSearchPage />);

    expect(screen.getByText(/검색어를 입력해 보세요/)).toBeInTheDocument();
    expect(searchSongsMock).not.toHaveBeenCalled();
  });

  it("검색어 입력 후 디바운스가 지나면 searchSongs를 호출하고 결과 카드를 렌더한다", async () => {
    const user = userEvent.setup();

    searchSongsMock.mockResolvedValueOnce([
      {
        id: 1,
        title: "Hello",
        artist: "Adele",
        releaseYear: 2015,
        keyOriginal: "F_MINOR",
        bpm: 79,
        mood: "EMOTIONAL",
        language: "en",
        genre: "POP",
        tjNumber: "12345",
        kyNumber: "54321",
        metadataSource: "MANUAL_SEED",
      },
    ]);

    renderWithQueryClient(<SongSearchPage />);

    const input = screen.getByPlaceholderText("곡 제목이나 아티스트로 검색");
    await user.type(input, "hello");

    await waitFor(
      () => {
        expect(searchSongsMock).toHaveBeenCalled();
      },
      { timeout: 2000 },
    );
    // 마지막 호출의 첫 인자(keyword)가 "hello"인지 확인. (signal은 두 번째 인자)
    const lastCall = searchSongsMock.mock.calls.at(-1)!;
    expect(lastCall[0]).toBe("hello");

    await waitFor(() => {
      expect(screen.getByText("Hello")).toBeInTheDocument();
    });
    expect(screen.getByText("Adele")).toBeInTheDocument();
    // closes #100 — 검색 카드는 곡 상세 페이지로 가는 링크가 되어야 한다.
    expect(
      screen.getByRole("link", { name: /Hello 상세 보기/ }),
    ).toHaveAttribute("href", "/songs/1");
  });

  it("응답이 빈 배열이면 '검색 결과 없음' fallback을 노출한다", async () => {
    const user = userEvent.setup();

    searchSongsMock.mockResolvedValueOnce([]);

    renderWithQueryClient(<SongSearchPage />);
    const input = screen.getByPlaceholderText("곡 제목이나 아티스트로 검색");
    await user.type(input, "zzzzzzz");

    await waitFor(
      () => {
        expect(searchSongsMock).toHaveBeenCalled();
      },
      { timeout: 2000 },
    );

    await waitFor(() => {
      expect(screen.getByText(/검색 결과 없음/)).toBeInTheDocument();
    });
  });

  it("난이도 필터를 누르면 응답을 client-side 필터링한다", async () => {
    const user = userEvent.setup();

    // 두 곡 응답: EASY 1건, HARD 1건. (deriveDifficulty 기준: highMidi>=76은 HARD)
    searchSongsMock.mockResolvedValueOnce([
      {
        id: 1,
        title: "쉬운 곡",
        artist: "A",
        releaseYear: 2024,
        keyOriginal: "C_MAJOR",
        bpm: 100,
        mood: "CALM",
        language: "ko",
        genre: "POP",
        tjNumber: null,
        kyNumber: null,
        metadataSource: "MANUAL_SEED",
        lowMidi: 48,
        highMidi: 60, // EASY (< 71)
      },
      {
        id: 2,
        title: "어려운 곡",
        artist: "B",
        releaseYear: 2024,
        keyOriginal: "C_MAJOR",
        bpm: 130,
        mood: "POWERFUL",
        language: "ko",
        genre: "ROCK",
        tjNumber: null,
        kyNumber: null,
        metadataSource: "MANUAL_SEED",
        lowMidi: 55,
        highMidi: 78, // HARD (>= 76)
      },
    ]);

    renderWithQueryClient(<SongSearchPage />);
    const input = screen.getByPlaceholderText("곡 제목이나 아티스트로 검색");
    await user.type(input, "song");

    await waitFor(
      () => {
        expect(screen.getByText("쉬운 곡")).toBeInTheDocument();
      },
      { timeout: 2000 },
    );
    expect(screen.getByText("어려운 곡")).toBeInTheDocument();

    // Hard 필터 → 어려운 곡만 남아야 한다.
    await user.click(screen.getByRole("button", { name: "Hard" }));
    await waitFor(() => {
      expect(screen.queryByText("쉬운 곡")).not.toBeInTheDocument();
    });
    expect(screen.getByText("어려운 곡")).toBeInTheDocument();
  });
});
