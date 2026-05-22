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
});
