/**
 * 곡 상세 페이지 테스트.
 *
 * 시나리오:
 *  - 정상 응답: 곡 제목/아티스트/난이도/최고음/최저음/메타 정보가 노출된다.
 *  - 404 응답: "곡을 찾을 수 없습니다" + 검색 페이지 CTA.
 *  - 잘못된 id (숫자 아님): readSongById를 호출하지 않고 즉시 NotFound.
 *
 * 모킹:
 *  - `next/navigation`의 useParams: id를 string으로 반환.
 *  - `@/lib/api/song.readSongById`: 응답을 통제.
 */

import { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { useParams } from "next/navigation";

import SongDetailPage from "./page";
import { ApiError } from "@/lib/api/client";
import { readSongById, type SongResponse } from "@/lib/api/song";
import { expectNoA11yViolations } from "@/lib/test-helpers/a11y";

vi.mock("next/navigation", () => ({
  useParams: vi.fn(),
}));
vi.mock("@/lib/api/song", async () => {
  const actual =
    await vi.importActual<typeof import("@/lib/api/song")>("@/lib/api/song");
  return {
    ...actual,
    readSongById: vi.fn(),
  };
});

const readSongByIdMock = vi.mocked(readSongById);
const useParamsMock = vi.mocked(useParams);

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

function buildSong(overrides: Partial<SongResponse> = {}): SongResponse {
  return {
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
    ...overrides,
  };
}

beforeEach(() => {
  readSongByIdMock.mockReset();
  useParamsMock.mockReset();
});

afterEach(() => {
  cleanup();
});

describe("SongDetailPage", () => {
  it("정상 응답이면 곡 정보를 카드 형태로 노출한다", async () => {
    useParamsMock.mockReturnValue({ id: "1" });
    readSongByIdMock.mockResolvedValueOnce(
      buildSong({ lowMidi: 55, highMidi: 77 }), // HARD, F5
    );

    renderWithQueryClient(<SongDetailPage />);

    await waitFor(() => {
      expect(screen.getByText("Hello")).toBeInTheDocument();
    });
    expect(screen.getByText("Adele")).toBeInTheDocument();
    expect(screen.getByLabelText(/가창 난이도 Hard/)).toBeInTheDocument();
    // 최고음 파5 (F5), 최저음 솔3 (G3) — #318 한국어 (SPN) 병기
    expect(screen.getByLabelText(/최고음 파5 \(F5\)/)).toBeInTheDocument();
    expect(screen.getByLabelText(/최저음 솔3 \(G3\)/)).toBeInTheDocument();
    // 키 라벨 (F Minor)
    expect(screen.getByText(/키 F Minor/)).toBeInTheDocument();
    // 메타 셀
    expect(screen.getByText("2015")).toBeInTheDocument();
    expect(screen.getByText("12345")).toBeInTheDocument();
    expect(screen.getByText("54321")).toBeInTheDocument();
    // CTA
    expect(
      screen.getByRole("link", { name: /비슷한 곡 추천 받기/ }),
    ).toHaveAttribute("href", "/recommend");
  });

  it("BE가 404를 주면 '곡을 찾을 수 없습니다' 안내와 검색 CTA를 노출한다", async () => {
    useParamsMock.mockReturnValue({ id: "9999" });
    readSongByIdMock.mockRejectedValueOnce(
      new ApiError(404, "song not found", { message: "song not found" }),
    );

    renderWithQueryClient(<SongDetailPage />);

    await waitFor(() => {
      expect(screen.getByText(/곡을 찾을 수 없습니다/)).toBeInTheDocument();
    });
    expect(readSongByIdMock).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("link", { name: /검색으로 가기/ })).toHaveAttribute(
      "href",
      "/songs",
    );
  });

  it("id 파라미터가 숫자가 아니면 API를 호출하지 않고 NotFound로 떨어진다", () => {
    useParamsMock.mockReturnValue({ id: "abc" });

    renderWithQueryClient(<SongDetailPage />);

    expect(screen.getByText(/곡을 찾을 수 없습니다/)).toBeInTheDocument();
    expect(readSongByIdMock).not.toHaveBeenCalled();
  });

  // closes #107 — 곡 상세 페이지의 정상 응답/404 두 상태에 대해 a11y 검사.
  describe("a11y", () => {
    it("정상 응답 렌더 상태에 a11y 위반이 없다", async () => {
      useParamsMock.mockReturnValue({ id: "1" });
      readSongByIdMock.mockResolvedValueOnce(
        buildSong({ lowMidi: 55, highMidi: 77 }),
      );

      const { container } = renderWithQueryClient(<SongDetailPage />);

      await waitFor(() => {
        expect(screen.getByText("Hello")).toBeInTheDocument();
      });

      await expectNoA11yViolations(container);
    });

    it("NotFound 상태에 a11y 위반이 없다", async () => {
      useParamsMock.mockReturnValue({ id: "abc" });
      const { container } = renderWithQueryClient(<SongDetailPage />);
      expect(screen.getByText(/곡을 찾을 수 없습니다/)).toBeInTheDocument();
      await expectNoA11yViolations(container);
    });
  });
});
