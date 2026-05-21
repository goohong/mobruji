/**
 * 곡 검색 페이지 테스트.
 *
 * 시나리오:
 *  - 초기: 검색어가 비어 있어 "검색어를 입력해 보세요" 가이드만 노출, API 호출 없음.
 *  - 검색어 입력 → 300ms 디바운스 → searchSongs 호출 → 결과 카드 리스트.
 *  - 응답이 빈 배열이면 "검색 결과 없음" fallback.
 *  - 난이도/장르 multi-select 필터, AND 조합, 초기화 버튼, URL 동기화 (#118).
 *
 * 구현 노트:
 *  - fake timer는 userEvent / React Query / waitFor와 결합 시 일관된 결과를 내기 어렵다.
 *    실제 setTimeout(300ms)을 그대로 흘러가게 두고, waitFor 타임아웃만 충분히(2초)
 *    잡아 디바운스 윈도우를 자연스럽게 통과한다. 300ms는 테스트 총 시간에 미미하다.
 *  - next/navigation은 happy-dom 환경에서 동작하지 않으므로 vi.mock으로 가짜 router를
 *    주입한다. useSearchParams는 테스트별 paramsRef로 갈아끼울 수 있게 한다.
 */

import { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import SongSearchPage from "./page";
import { searchSongs } from "@/lib/api/song";
import { expectNoA11yViolations } from "@/lib/test-helpers/a11y";

// next/navigation의 useRouter / useSearchParams 가짜 구현.
// paramsRef로 테스트별 초기 query를 주입할 수 있다.
const { replaceMock, paramsRef } = await vi.hoisted(async () => {
  return {
    replaceMock: vi.fn(),
    paramsRef: { current: new URLSearchParams() },
  };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: replaceMock, push: vi.fn() }),
  useSearchParams: () => paramsRef.current,
}));

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
  replaceMock.mockReset();
  paramsRef.current = new URLSearchParams();
});

afterEach(() => {
  cleanup();
});

// 두 곡 응답: POP/EASY, ROCK/HARD. deriveDifficulty 기준 highMidi>=76은 HARD, <71은 EASY.
function twoSongsResponse() {
  return [
    {
      id: 1,
      title: "쉬운 곡",
      artist: "A",
      releaseYear: 2024,
      keyOriginal: "C_MAJOR" as const,
      bpm: 100,
      mood: "CALM" as const,
      language: "ko",
      genre: "POP",
      tjNumber: null,
      kyNumber: null,
      metadataSource: "MANUAL_SEED" as const,
      lowMidi: 48,
      highMidi: 60, // EASY
    },
    {
      id: 2,
      title: "어려운 곡",
      artist: "B",
      releaseYear: 2024,
      keyOriginal: "C_MAJOR" as const,
      bpm: 130,
      mood: "POWERFUL" as const,
      language: "ko",
      genre: "ROCK",
      tjNumber: null,
      kyNumber: null,
      metadataSource: "MANUAL_SEED" as const,
      lowMidi: 55,
      highMidi: 78, // HARD
    },
  ];
}

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

  it("난이도 필터 칩을 누르면 응답을 client-side 필터링한다", async () => {
    const user = userEvent.setup();

    searchSongsMock.mockResolvedValueOnce(twoSongsResponse());

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

  it("장르 필터 단일 선택은 해당 장르 곡만 남긴다 (#118)", async () => {
    const user = userEvent.setup();

    searchSongsMock.mockResolvedValueOnce(twoSongsResponse());

    renderWithQueryClient(<SongSearchPage />);
    await user.type(
      screen.getByPlaceholderText("곡 제목이나 아티스트로 검색"),
      "song",
    );

    await waitFor(
      () => {
        expect(screen.getByText("쉬운 곡")).toBeInTheDocument();
      },
      { timeout: 2000 },
    );

    // 장르 chip은 응답의 unique genre들로 동적 생성됨 (POP, ROCK).
    await user.click(screen.getByRole("button", { name: "POP" }));

    await waitFor(() => {
      expect(screen.queryByText("어려운 곡")).not.toBeInTheDocument();
    });
    expect(screen.getByText("쉬운 곡")).toBeInTheDocument();
    // 결과 카운트도 갱신.
    expect(screen.getByText(/필터 결과 1곡 \/ 전체 2곡/)).toBeInTheDocument();
  });

  it("장르 + 난이도 필터는 AND 조건으로 결합된다 (#118)", async () => {
    const user = userEvent.setup();

    // POP/EASY, ROCK/HARD, POP/HARD 세 곡.
    searchSongsMock.mockResolvedValueOnce([
      ...twoSongsResponse(),
      {
        id: 3,
        title: "팝 어려운 곡",
        artist: "C",
        releaseYear: 2024,
        keyOriginal: "C_MAJOR" as const,
        bpm: 110,
        mood: "POWERFUL" as const,
        language: "ko",
        genre: "POP",
        tjNumber: null,
        kyNumber: null,
        metadataSource: "MANUAL_SEED" as const,
        lowMidi: 55,
        highMidi: 78, // HARD
      },
    ]);

    renderWithQueryClient(<SongSearchPage />);
    await user.type(
      screen.getByPlaceholderText("곡 제목이나 아티스트로 검색"),
      "song",
    );

    await waitFor(
      () => {
        expect(screen.getByText("팝 어려운 곡")).toBeInTheDocument();
      },
      { timeout: 2000 },
    );

    // POP AND Hard → 'POP/HARD'인 곡 1건만.
    await user.click(screen.getByRole("button", { name: "POP" }));
    await user.click(screen.getByRole("button", { name: "Hard" }));

    await waitFor(() => {
      expect(screen.queryByText("쉬운 곡")).not.toBeInTheDocument();
    });
    expect(screen.queryByText("어려운 곡")).not.toBeInTheDocument();
    expect(screen.getByText("팝 어려운 곡")).toBeInTheDocument();
    expect(screen.getByText(/필터 결과 1곡 \/ 전체 3곡/)).toBeInTheDocument();
  });

  it("'필터 초기화' 버튼을 누르면 모든 필터가 해제되고 전체 결과가 복귀한다 (#118)", async () => {
    const user = userEvent.setup();
    searchSongsMock.mockResolvedValueOnce(twoSongsResponse());

    renderWithQueryClient(<SongSearchPage />);
    await user.type(
      screen.getByPlaceholderText("곡 제목이나 아티스트로 검색"),
      "song",
    );

    await waitFor(
      () => {
        expect(screen.getByText("쉬운 곡")).toBeInTheDocument();
      },
      { timeout: 2000 },
    );

    await user.click(screen.getByRole("button", { name: "Easy" }));
    await waitFor(() => {
      expect(screen.queryByText("어려운 곡")).not.toBeInTheDocument();
    });

    // 초기화 버튼은 활성 필터가 있을 때만 노출됨.
    const resetButton = screen.getByRole("button", { name: "필터 초기화" });
    await user.click(resetButton);

    await waitFor(() => {
      expect(screen.getByText("어려운 곡")).toBeInTheDocument();
    });
    expect(screen.getByText("쉬운 곡")).toBeInTheDocument();
    // 초기화 후엔 버튼 자체가 사라진다.
    expect(
      screen.queryByRole("button", { name: "필터 초기화" }),
    ).not.toBeInTheDocument();
  });

  it("URL query를 초기값으로 사용해 keyword/필터를 복원한다 (#118)", async () => {
    paramsRef.current = new URLSearchParams(
      "keyword=song&genre=ROCK&difficulty=HARD",
    );
    searchSongsMock.mockResolvedValueOnce(twoSongsResponse());

    renderWithQueryClient(<SongSearchPage />);

    // 디바운스 없이도 초기 keyword가 'song'이라 즉시 검색이 시작된다.
    await waitFor(
      () => {
        expect(searchSongsMock).toHaveBeenCalled();
      },
      { timeout: 2000 },
    );
    expect(searchSongsMock.mock.calls.at(-1)?.[0]).toBe("song");

    // URL에 따라 ROCK + HARD만 남는다.
    await waitFor(() => {
      expect(screen.getByText("어려운 곡")).toBeInTheDocument();
    });
    expect(screen.queryByText("쉬운 곡")).not.toBeInTheDocument();

    // chip의 aria-pressed가 URL 상태를 반영해야 한다.
    expect(screen.getByRole("button", { name: "Hard" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(screen.getByRole("button", { name: "ROCK" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
  });

  it("필터 토글 시 router.replace로 URL을 동기화한다 (#118)", async () => {
    const user = userEvent.setup();
    searchSongsMock.mockResolvedValueOnce(twoSongsResponse());

    renderWithQueryClient(<SongSearchPage />);
    await user.type(
      screen.getByPlaceholderText("곡 제목이나 아티스트로 검색"),
      "song",
    );

    await waitFor(
      () => {
        expect(screen.getByText("쉬운 곡")).toBeInTheDocument();
      },
      { timeout: 2000 },
    );

    replaceMock.mockClear();
    await user.click(screen.getByRole("button", { name: "Easy" }));

    await waitFor(() => {
      expect(replaceMock).toHaveBeenCalled();
    });
    const lastUrl = replaceMock.mock.calls.at(-1)?.[0] as string;
    expect(lastUrl).toContain("keyword=song");
    expect(lastUrl).toContain("difficulty=EASY");
  });

  // closes #107 — 검색 페이지는 search input, 난이도 필터 칩(aria-pressed),
  // 결과 카드 리스트, 빈 결과 fallback이 한 화면에 공존. 각 상태별 a11y 검사.
  describe("a11y", () => {
    it("초기(검색어 미입력) 상태에 a11y 위반이 없다", async () => {
      const { container } = renderWithQueryClient(<SongSearchPage />);
      await expectNoA11yViolations(container);
    });

    it("검색 결과 카드 렌더 상태에 a11y 위반이 없다", async () => {
      const user = userEvent.setup();
      searchSongsMock.mockResolvedValueOnce([
        {
          id: 1,
          title: "a11y 곡",
          artist: "Tester",
          releaseYear: 2024,
          keyOriginal: "C_MAJOR",
          bpm: 100,
          mood: "UPBEAT",
          language: "ko",
          genre: "POP",
          tjNumber: null,
          kyNumber: null,
          metadataSource: "MANUAL_SEED",
          lowMidi: 48,
          highMidi: 70,
        },
      ]);

      const { container } = renderWithQueryClient(<SongSearchPage />);
      await user.type(
        screen.getByPlaceholderText("곡 제목이나 아티스트로 검색"),
        "a11y",
      );

      await waitFor(
        () => {
          expect(screen.getByText("a11y 곡")).toBeInTheDocument();
        },
        { timeout: 2000 },
      );

      await expectNoA11yViolations(container);
    });

    it("'검색 결과 없음' fallback 상태에 a11y 위반이 없다", async () => {
      const user = userEvent.setup();
      searchSongsMock.mockResolvedValueOnce([]);

      const { container } = renderWithQueryClient(<SongSearchPage />);
      await user.type(
        screen.getByPlaceholderText("곡 제목이나 아티스트로 검색"),
        "zzz",
      );

      await waitFor(
        () => {
          expect(screen.getByText(/검색 결과 없음/)).toBeInTheDocument();
        },
        { timeout: 2000 },
      );

      await expectNoA11yViolations(container);
    });
  });
});
