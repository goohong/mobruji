/**
 * 앨범 커버 표시 단위 테스트 (closes #322).
 *
 * 검증:
 *  - albumCoverUrl 가 string 이면 <img src> + alt 가 그려진다.
 *  - albumCoverUrl 가 null/undefined 이면 placeholder(role="img" + 음표 SVG)로 fallback.
 *  - img onError 시 placeholder 로 fallback (BE 응답 URL 이 404/CORS 등으로 실패한 경우).
 *  - thumbnail 사이즈는 loading="lazy" 가 적용된다.
 *
 * SongDetailContent 안의 AlbumCover 는 export 되지 않으므로(모달 전용), 직접 export 된
 * `AlbumCoverThumbnail` 과 SongDetailContent 컴포넌트를 통해 간접 검증한다.
 */

import { afterEach, describe, expect, it } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ReactNode } from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

import {
  AlbumCoverThumbnail,
  SongDetailContent,
} from "./SongDetailContent";
import type { SongResponse } from "@/lib/api/recommendation";

function renderWithQueryClient(ui: ReactNode) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    );
  }
  return render(ui, { wrapper: Wrapper });
}

function buildSong(overrides: Partial<SongResponse> = {}): SongResponse {
  return {
    id: 1,
    title: "테스트 곡",
    artist: "가수",
    releaseYear: 2024,
    keyOriginal: "C_MAJOR",
    bpm: 120,
    mood: "UPBEAT",
    language: "ko",
    genre: "POP",
    tjNumber: null,
    kyNumber: null,
    metadataSource: "MANUAL_SEED",
    ...overrides,
  };
}

afterEach(() => {
  cleanup();
});

describe("AlbumCoverThumbnail (closes #322)", () => {
  it("albumCoverUrl 이 string 이면 <img> 가 lazy load 로 렌더된다", () => {
    const song = buildSong({ albumCoverUrl: "https://example.com/cover.jpg" });
    render(<AlbumCoverThumbnail song={song} />);
    const img = screen.getByAltText("테스트 곡 앨범 커버") as HTMLImageElement;
    expect(img).toBeInTheDocument();
    expect(img.tagName).toBe("IMG");
    expect(img.getAttribute("src")).toBe("https://example.com/cover.jpg");
    expect(img.getAttribute("loading")).toBe("lazy");
  });

  it("albumCoverUrl 이 null 이면 placeholder(role=img + 음표 SVG)로 fallback 한다", () => {
    const song = buildSong({ albumCoverUrl: null });
    render(<AlbumCoverThumbnail song={song} />);
    expect(
      screen.getByLabelText(/테스트 곡 앨범 커버 \(이미지 없음\)/),
    ).toBeInTheDocument();
    expect(screen.queryByRole("img", { name: /앨범 커버$/ })).toBeNull();
  });

  it("albumCoverUrl 이 undefined 여도 placeholder 로 안전 fallback", () => {
    const song = buildSong(); // albumCoverUrl 미지정
    render(<AlbumCoverThumbnail song={song} />);
    expect(
      screen.getByLabelText(/테스트 곡 앨범 커버 \(이미지 없음\)/),
    ).toBeInTheDocument();
  });

  it("img onError 시 placeholder 로 fallback", () => {
    const song = buildSong({ albumCoverUrl: "https://example.com/404.jpg" });
    render(<AlbumCoverThumbnail song={song} />);
    const img = screen.getByAltText("테스트 곡 앨범 커버") as HTMLImageElement;
    fireEvent.error(img);
    expect(
      screen.getByLabelText(/테스트 곡 앨범 커버 \(이미지 없음\)/),
    ).toBeInTheDocument();
  });
});

describe("SongDetailContent 의 large AlbumCover", () => {
  it("albumCoverUrl 이 string 이면 모달 큰 사이즈 <img> 가 그려진다", () => {
    const song = buildSong({ albumCoverUrl: "https://example.com/big.jpg" });
    renderWithQueryClient(<SongDetailContent song={song} />);
    const img = screen.getByAltText("테스트 곡 앨범 커버") as HTMLImageElement;
    expect(img.getAttribute("src")).toBe("https://example.com/big.jpg");
    // 모달의 큰 이미지는 lazy 가 아니다 (오픈 시 즉시 보이게).
    expect(img.getAttribute("loading")).not.toBe("lazy");
  });

  it("albumCoverUrl null → placeholder로 fallback", () => {
    const song = buildSong({ albumCoverUrl: null });
    renderWithQueryClient(<SongDetailContent song={song} />);
    expect(
      screen.getByLabelText(/테스트 곡 앨범 커버 \(이미지 없음\)/),
    ).toBeInTheDocument();
  });

  it("img onError 시 placeholder 로 fallback (closes #499)", () => {
    const song = buildSong({ albumCoverUrl: "https://example.com/404-large.jpg" });
    renderWithQueryClient(<SongDetailContent song={song} />);
    const img = screen.getByAltText("테스트 곡 앨범 커버") as HTMLImageElement;
    fireEvent.error(img);
    expect(
      screen.getByLabelText(/테스트 곡 앨범 커버 \(이미지 없음\)/),
    ).toBeInTheDocument();
  });
});

/*
 * PR #500 후속 회귀 가드 (closes #529):
 *  - onError fallback 후 placeholder size 차등 (large=h-48 vs thumbnail=h-14)
 *  - onError → 동일 song rerender 시에도 placeholder 유지 (failed state 보존)
 *  - 동일 img onError 두 번 연속 호출되어도 안전 (idempotent fallback)
 */
describe("onError fallback 회귀 가드 (PR #500 후속, closes #529)", () => {
  it("large onError fallback placeholder 는 h-48 사이즈로 렌더된다", () => {
    const song = buildSong({ albumCoverUrl: "https://example.com/big-404.jpg" });
    renderWithQueryClient(<SongDetailContent song={song} />);
    fireEvent.error(screen.getByAltText("테스트 곡 앨범 커버"));
    const placeholder = screen.getByLabelText(/테스트 곡 앨범 커버 \(이미지 없음\)/);
    expect(placeholder.className).toMatch(/h-48/);
    expect(placeholder.className).toMatch(/w-48/);
  });

  it("thumbnail onError fallback placeholder 는 h-14 사이즈로 렌더된다", () => {
    const song = buildSong({ albumCoverUrl: "https://example.com/thumb-404.jpg" });
    render(<AlbumCoverThumbnail song={song} />);
    fireEvent.error(screen.getByAltText("테스트 곡 앨범 커버"));
    const placeholder = screen.getByLabelText(/테스트 곡 앨범 커버 \(이미지 없음\)/);
    expect(placeholder.className).toMatch(/h-14/);
    expect(placeholder.className).toMatch(/w-14/);
  });

  it("thumbnail onError 후 동일 song rerender 해도 placeholder 유지 (failed state 보존)", () => {
    const song = buildSong({ albumCoverUrl: "https://example.com/thumb-cors.jpg" });
    const { rerender } = render(<AlbumCoverThumbnail song={song} />);
    fireEvent.error(screen.getByAltText("테스트 곡 앨범 커버"));
    rerender(<AlbumCoverThumbnail song={song} />);
    expect(
      screen.getByLabelText(/테스트 곡 앨범 커버 \(이미지 없음\)/),
    ).toBeInTheDocument();
    expect(screen.queryByAltText("테스트 곡 앨범 커버")).toBeNull();
  });

  it("thumbnail onError 가 두 번 연속 호출되어도 안전 fallback (idempotent)", () => {
    const song = buildSong({ albumCoverUrl: "https://example.com/double-error.jpg" });
    render(<AlbumCoverThumbnail song={song} />);
    const img = screen.getByAltText("테스트 곡 앨범 커버");
    fireEvent.error(img);
    // 1차 fallback 후 img 가 제거되므로 같은 노드에 두 번째 error 를 fire 해도
    // 컴포넌트는 이미 placeholder 상태 — 에러 throw 없이 그대로 유지.
    fireEvent.error(img);
    expect(
      screen.getByLabelText(/테스트 곡 앨범 커버 \(이미지 없음\)/),
    ).toBeInTheDocument();
  });
});

/*
 * Placeholder 접근성 회귀 가드 (closes #531):
 *  - placeholder 외부 컨테이너에 role="img" + aria-label 이 있으므로 내부 음표 SVG 는
 *    스크린리더 이중 읽기 방지를 위해 aria-hidden="true" 여야 한다.
 *  - large / thumbnail 양쪽 모두 동일 규칙 적용.
 */
describe("AlbumCoverPlaceholder MusicNoteIcon aria-hidden 회귀 가드 (closes #531)", () => {
  it("thumbnail placeholder 내부 SVG 는 aria-hidden='true'", () => {
    const song = buildSong({ albumCoverUrl: null });
    const { container } = render(<AlbumCoverThumbnail song={song} />);
    const svg = container.querySelector("svg");
    expect(svg).not.toBeNull();
    expect(svg?.getAttribute("aria-hidden")).toBe("true");
  });

  it("large placeholder 내부 SVG 는 aria-hidden='true'", () => {
    const song = buildSong({ albumCoverUrl: null });
    renderWithQueryClient(<SongDetailContent song={song} />);
    const placeholder = screen.getByLabelText(/테스트 곡 앨범 커버 \(이미지 없음\)/);
    const svg = placeholder.querySelector("svg");
    expect(svg).not.toBeNull();
    expect(svg?.getAttribute("aria-hidden")).toBe("true");
  });
});
