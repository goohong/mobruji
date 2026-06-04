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
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
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
    expect(screen.getByLabelText(/가창 난이도 어려움/)).toBeInTheDocument();
    // 최고음 파5, 최저음 솔3 — 한국어 단독 (#1310 사용자 정정 2026-06-03)
    expect(screen.getByLabelText(/최고음 파5/)).toBeInTheDocument();
    expect(screen.getByLabelText(/최저음 솔3/)).toBeInTheDocument();
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

  // closes #445 — 일시 오류(5xx) 분기는 role="alert" + aria-live="assertive"로
  // 스크린리더가 즉시 안내할 수 있어야 한다. 회귀 가드.
  it("일시 오류(5xx) 분기는 role='alert' aria-live='assertive' 컨테이너로 노출된다", async () => {
    useParamsMock.mockReturnValue({ id: "1" });
    // useQuery retry=1 (404 외 1회 재시도) → 두 번 모두 5xx 일관 응답이어야
    // alert 분기에 진입한다. mockResolvedValueOnce 단일 사용 시 두번째 호출이
    // undefined 로 resolve → query success 처리되어 alert 미노출 (#745).
    readSongByIdMock.mockRejectedValue(
      new ApiError(500, "internal error", { message: "internal error" }),
    );

    renderWithQueryClient(<SongDetailPage />);

    // useQuery retry=1 + 기본 retryDelay(1초) → 첫 시도 + 1초 대기 + 두번째 시도 후 isError.
    // findByRole default timeout(1초) 부족 → 3초로 확장 (#745).
    const alert = await screen.findByRole("alert", undefined, { timeout: 3000 });
    expect(alert).toHaveAttribute("aria-live", "assertive");
    expect(alert).toHaveTextContent(/곡 정보를 불러오지 못했습니다/);
    expect(alert).toHaveTextContent(/500/);
  });

  // closes #480 — SongDetailSkeleton(isPending 분기)는 SR 사용자가
  // "로딩 중"을 인지하도록 role="status" + aria-busy="true" + aria-label 을
  // 부여한다. /songs 검색 페이지의 loading status 가드(#472) 와 정합.
  // 향후 skeleton 리팩터링 시 셋 중 하나라도 누락되면 이 테스트가 실패한다.
  it("isPending 분기는 role='status' aria-busy='true' aria-label 컨테이너로 노출된다 (#480)", () => {
    useParamsMock.mockReturnValue({ id: "1" });
    // resolve/reject 둘 다 하지 않는 promise → query 가 isPending 상태로 고정된다.
    // never-settling promise 라 다음 테스트로 GC 되더라도 leak 없음 (mockReset 호출됨).
    readSongByIdMock.mockReturnValueOnce(new Promise<SongResponse>(() => {}));

    renderWithQueryClient(<SongDetailPage />);

    // role=status 는 SR polite live region. axe 도 status 안의 aria-busy 를 허용한다.
    const status = screen.getByRole("status");
    expect(status).toHaveAttribute("aria-busy", "true");
    expect(status).toHaveAttribute("aria-label", "곡 정보를 불러오는 중");
  });

  it("id 파라미터가 숫자가 아니면 API를 호출하지 않고 NotFound로 떨어진다", () => {
    useParamsMock.mockReturnValue({ id: "abc" });

    renderWithQueryClient(<SongDetailPage />);

    expect(screen.getByText(/곡을 찾을 수 없습니다/)).toBeInTheDocument();
    expect(readSongByIdMock).not.toHaveBeenCalled();
  });

  // closes #1666 — 단건 상세 페이지에도 앨범 커버를 노출한다. 카드/모달과 동일한
  // SongDetailContent 의 large AlbumCover 를 재사용하므로 placeholder/onError fallback
  // 동작이 그대로 따라온다.
  describe("앨범 커버 (closes #1666)", () => {
    it("albumCoverUrl 이 string 이면 <img> 가 렌더된다", async () => {
      useParamsMock.mockReturnValue({ id: "1" });
      readSongByIdMock.mockResolvedValueOnce(
        buildSong({ albumCoverUrl: "https://example.com/cover.jpg" }),
      );

      renderWithQueryClient(<SongDetailPage />);

      const img = (await screen.findByAltText(
        "Hello 앨범 커버",
      )) as HTMLImageElement;
      expect(img.tagName).toBe("IMG");
      expect(img.getAttribute("src")).toBe("https://example.com/cover.jpg");
    });

    it("albumCoverUrl 이 null 이면 placeholder 로 fallback 한다", async () => {
      useParamsMock.mockReturnValue({ id: "1" });
      readSongByIdMock.mockResolvedValueOnce(
        buildSong({ albumCoverUrl: null }),
      );

      renderWithQueryClient(<SongDetailPage />);

      await waitFor(() => {
        expect(
          screen.getByLabelText(/Hello 앨범 커버 \(이미지 없음\)/),
        ).toBeInTheDocument();
      });
      expect(screen.queryByRole("img", { name: /Hello 앨범 커버$/ })).toBeNull();
    });

    it("img onError 시 placeholder 로 fallback 한다", async () => {
      useParamsMock.mockReturnValue({ id: "1" });
      readSongByIdMock.mockResolvedValueOnce(
        buildSong({ albumCoverUrl: "https://example.com/404.jpg" }),
      );

      renderWithQueryClient(<SongDetailPage />);

      const img = await screen.findByAltText("Hello 앨범 커버");
      fireEvent.error(img);
      expect(
        screen.getByLabelText(/Hello 앨범 커버 \(이미지 없음\)/),
      ).toBeInTheDocument();
    });

    // closes #1687 (PR7) — 카드 thumbnail 과 같은 album-{id} 이름을 cover 에 줘서
    // /history → /songs/[id] 라우트 전환 시 hero morph 한다.
    it("cover 에 album-{id} view-transition-name 이 적용된다 (hero morph)", async () => {
      useParamsMock.mockReturnValue({ id: "1" });
      readSongByIdMock.mockResolvedValueOnce(
        buildSong({ albumCoverUrl: "https://example.com/cover.jpg" }),
      );

      renderWithQueryClient(<SongDetailPage />);

      const img = (await screen.findByAltText(
        "Hello 앨범 커버",
      )) as HTMLImageElement;
      expect(img.style.viewTransitionName).toBe("album-1");
    });
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
