/**
 * SongDetailContent 테스트 (closes #493) — SongCard 의 closes #486 alert
 * aria-live 패턴이 모달 본문 DetailLikeButton/DetailBookmarkButton 에도 유지되는지
 * 회귀 가드 + 기본 props pass-through smoke.
 */

import { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { SongDetailContent } from "./SongDetailContent";
import type { SongResponse } from "@/lib/api/recommendation";
import { useBookmarksStore } from "@/store/bookmarks";
import { useLikesStore } from "@/store/likes";
import { useSessionStore } from "@/store/session";

vi.mock("@/lib/api/feedback", () => ({
  toggleLike: vi.fn(),
  toggleBookmark: vi.fn(),
}));

import { toggleBookmark, toggleLike } from "@/lib/api/feedback";

const toggleLikeMock = vi.mocked(toggleLike);
const toggleBookmarkMock = vi.mocked(toggleBookmark);

function renderWithQueryClient(ui: ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const Wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  return render(ui, { wrapper: Wrapper });
}

const SONG: SongResponse = {
  id: 1,
  title: "테스트 곡",
  artist: "테스트 가수",
  releaseYear: 2024,
  keyOriginal: "C_MAJOR",
  bpm: 120,
  mood: "UPBEAT",
  language: "ko",
  genre: "POP",
  tjNumber: null,
  kyNumber: null,
  metadataSource: "MANUAL_SEED",
};

beforeEach(() => {
  useLikesStore.setState({ likedSongIds: [] });
  useBookmarksStore.setState({ bookmarkedSongIds: [] });
  useSessionStore.setState({
    sessionId: "test-session-id",
    voiceRangeId: null,
    excludedSongIds: [],
  });
  if (typeof localStorage !== "undefined") {
    ["mobruji-likes", "mobruji-bookmarks", "mobruji-session"].forEach((k) =>
      localStorage.removeItem(k),
    );
  }
  toggleLikeMock.mockReset();
  toggleBookmarkMock.mockReset();
  toggleLikeMock.mockResolvedValue({ liked: true, songId: 1 });
  toggleBookmarkMock.mockResolvedValue({ bookmarked: true, songId: 1 });
});

afterEach(() => cleanup());

describe("SongDetailContent", () => {
  it("song props 의 제목/아티스트가 그대로 렌더된다 (smoke)", () => {
    renderWithQueryClient(<SongDetailContent song={SONG} />);
    expect(screen.getByText("테스트 가수")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /테스트 곡 좋아요$/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /테스트 곡 북마크$/ })).toBeInTheDocument();
  });

  it("DetailLikeButton: mutation 실패 시 alert 에 aria-live=\"assertive\" 가 부여된다", async () => {
    const user = userEvent.setup();
    toggleLikeMock.mockRejectedValueOnce(new Error("network down"));
    renderWithQueryClient(<SongDetailContent song={SONG} />);
    await user.click(screen.getByRole("button", { name: /테스트 곡 좋아요$/ }));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(/좋아요 처리에 실패했어요/);
    expect(alert).toHaveAttribute("aria-live", "assertive");
  });

  it("DetailBookmarkButton: mutation 실패 시 alert 에 aria-live=\"assertive\" 가 부여된다", async () => {
    const user = userEvent.setup();
    toggleBookmarkMock.mockRejectedValueOnce(new Error("network down"));
    renderWithQueryClient(<SongDetailContent song={SONG} />);
    await user.click(screen.getByRole("button", { name: /테스트 곡 북마크$/ }));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(/북마크 처리에 실패했어요/);
    expect(alert).toHaveAttribute("aria-live", "assertive");
  });

  // closes #525 — SongCard 의 PR #524 와 같은 회귀 가드.
  // onError 에서 store 토글 재호출이 누락되면 낙관값이 그대로 굳어 사용자에게 가짜 성공이
  // 노출된다. alert aria-live 가드만으로는 잡히지 않는 사일런트 회귀라 store + 버튼
  // aria-pressed 양쪽을 검증한다.
  it("DetailLikeButton: mutation 실패 시 낙관적 변경을 롤백한다 (store + aria-pressed)", async () => {
    const user = userEvent.setup();
    toggleLikeMock.mockRejectedValueOnce(new Error("network down"));
    renderWithQueryClient(<SongDetailContent song={SONG} />);
    await user.click(screen.getByRole("button", { name: /테스트 곡 좋아요$/ }));
    await waitFor(() => {
      expect(useLikesStore.getState().likedSongIds).toEqual([]);
    });
    expect(
      screen.getByRole("button", { name: /테스트 곡 좋아요$/ }),
    ).toHaveAttribute("aria-pressed", "false");
  });

  it("DetailBookmarkButton: mutation 실패 시 낙관적 변경을 롤백한다 (store + aria-pressed)", async () => {
    const user = userEvent.setup();
    toggleBookmarkMock.mockRejectedValueOnce(new Error("network down"));
    renderWithQueryClient(<SongDetailContent song={SONG} />);
    await user.click(screen.getByRole("button", { name: /테스트 곡 북마크$/ }));
    await waitFor(() => {
      expect(useBookmarksStore.getState().bookmarkedSongIds).toEqual([]);
    });
    expect(
      screen.getByRole("button", { name: /테스트 곡 북마크$/ }),
    ).toHaveAttribute("aria-pressed", "false");
  });

  // closes #535 — YouTubeSearchLink 는 새 탭으로 외부 사이트(youtube.com) 를 열기 때문에
  // reverse tabnabbing 방지를 위해 rel="noopener noreferrer" 가 반드시 함께 있어야 한다.
  // 누군가 rel 을 누락하거나 target 을 바꿔도 사일런트 회귀라 명시적 가드를 둔다.
  it("YouTubeSearchLink: target=_blank 와 rel=noopener noreferrer 를 함께 가진다", () => {
    renderWithQueryClient(<SongDetailContent song={SONG} />);
    const link = screen.getByRole("link", { name: /테스트 곡 YouTube에서 듣기/ });
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noopener noreferrer");
  });
});
