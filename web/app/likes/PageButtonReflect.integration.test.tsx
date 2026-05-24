/**
 * /likes 페이지 컨텍스트에서 SongCard.LikeButton / SongCard.BookmarkButton 의
 * hook → UI reflect 통합 회귀 가드 (PR #989 후속).
 *
 * AS-IS:
 *   - PR #985 가 hook 레벨 (`useLikeToggleMutation` / `useBookmarkToggleMutation`)
 *     race / unmount / 401 가드 6건을 추가.
 *   - PR #989 가 SongCard / SongDetailContent **직접 마운트** 환경에서 disabled +
 *     aria-busy + 401 alert + optimistic UI 의 4 컴포넌트 × 3 시나리오 = 16 통합
 *     가드 추가.
 *   - 그러나 실제 사용자 흐름은 `/likes` 페이지 → BE GET wrapper 로딩 → SongCard
 *     리스트 마운트 → 토글 클릭이다. **페이지 컨텍스트** (BE GET mock + zustand
 *     store sync + React Query invalidation 결합) 에서 동일 가드가 작동하는지는
 *     검증 부재. 누군가 LikesPage 가 SongCard prop 시그니처를 바꾸거나, store
 *     동기화가 LikeButton 의 `aria-pressed` 분기를 마스킹하면 회귀 미검출.
 *
 * TO-BE:
 *   본 파일은 `/likes` 페이지 마운트 → SongCard.LikeButton / SongCard.BookmarkButton
 *   2 버튼 × 3 시나리오 = 6 페이지 레벨 통합 가드.
 *     A. pending 상태 → disabled + aria-busy="true" reflect
 *     B. 401 ApiError → 한국어 alert (aria-live=assertive) reflect
 *     C. 정상 toggle → optimistic UI 즉시 update (aria-pressed false→true)
 *
 *   PR #989 와 다른 점: **mount target 이 LikesPage** 라 BE GET wrapper 응답이
 *   먼저 도착해 SongCard 가 N개 마운트되는 page-context flow 를 cover. 같은 시나리오
 *   를 두 layer (component / page) 에서 박아 page composition 회귀 (예: store
 *   wiring / Query key 어긋남) 가 SongCard 단독 테스트로 누락되는 시나리오를 차단.
 *
 * 비범위:
 *   - 페이지 본체 / hook / SongCard 컴포넌트 변경 없음 (테스트만 추가).
 *   - hook 자체 race / unmount / 401 흐름 — PR #985 hook 테스트 cover.
 *   - SongCard / SongDetailContent 직접 마운트 가드 — PR #989 cover.
 *   - auto-dismiss 정확 duration — useAutoDismissMessage.test.ts (lib 레벨) cover.
 *   - /bookmarks 페이지 동일 가드 — `web/app/bookmarks/PageButtonReflect.integration.test.tsx`
 *     에서 대칭으로 cover.
 *
 * 보안:
 *   - sessionId 는 PII (logging.ts §SENSITIVE_KEYS). 본 테스트의 "test-session-id"
 *     는 의도된 더미값으로 raw 노출 안전.
 */

import { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import LikesPage from "./page";
import {
  readLikesBySessionId,
  type LikeListResponse,
  type LikeWithSongResponse,
} from "@/lib/api/feedback";
import { ApiError } from "@/lib/api/client";
import type { SongResponse } from "@/lib/api/song";
import { useBookmarksStore } from "@/store/bookmarks";
import { useLikesStore } from "@/store/likes";
import { useSessionStore } from "@/store/session";

vi.mock("@/lib/api/feedback", () => ({
  readLikesBySessionId: vi.fn(),
  readBookmarksBySessionId: vi.fn(),
  toggleLike: vi.fn(),
  toggleBookmark: vi.fn(),
}));

import { toggleBookmark, toggleLike } from "@/lib/api/feedback";

const readLikesMock = vi.mocked(readLikesBySessionId);
const toggleLikeMock = vi.mocked(toggleLike);
const toggleBookmarkMock = vi.mocked(toggleBookmark);

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
    return (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    );
  }
  return render(ui, { wrapper: Wrapper });
}

/**
 * `mutationFn` 이 mount 단계에서 즉시 resolve/reject 되지 않고 외부에서 컨트롤 가능한
 * Promise 를 돌려주도록 한다. 시나리오 A (pending 반영) 검증의 핵심 도구.
 *
 * happy-dom 환경에서 같은 micro-task 안에 pending 상태를 관찰하기 위해 의도적으로
 * resolve 를 지연시킨다 — PR #989 의 deferred 와 동일 형태.
 */
function deferred<T>(): {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (error: unknown) => void;
} {
  let resolveFn: (value: T) => void = () => undefined;
  let rejectFn: (error: unknown) => void = () => undefined;
  const promise = new Promise<T>((res, rej) => {
    resolveFn = res;
    rejectFn = rej;
  });
  return { promise, resolve: resolveFn, reject: rejectFn };
}

/**
 * BE 401 응답을 흉내내는 ApiError. PR #985 의 hook 401 가드와 동일 형태로 hook 의
 * onError 분기 (낙관 토글 롤백 + 한국어 안내 + 자동 dismiss) 를 트리거한다.
 */
function unauthorizedError(): ApiError {
  return new ApiError(401, "Unauthorized", { error: "UNAUTHORIZED" });
}

beforeEach(() => {
  readLikesMock.mockReset();
  toggleLikeMock.mockReset();
  toggleBookmarkMock.mockReset();
  // /likes 페이지가 마운트하자마자 GET 을 쏘므로 default 로 단일 곡 wrapper 응답.
  // 각 테스트에서 SongCard 1건이 마운트돼 토글 대상이 된다.
  readLikesMock.mockResolvedValue(buildWrapper([1]));
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
});

afterEach(() => {
  cleanup();
  // 본 파일은 fake timer 를 사용하지 않으나 다른 테스트 leak 대비 safety net.
  vi.useRealTimers();
});

describe("/likes 페이지 — Button UI reflect 통합 가드 (PR #989 후속)", () => {
  // 시나리오 A — pending 상태 → disabled + aria-busy="true" reflect
  //
  // 페이지가 BE GET wrapper 응답 후 SongCard 를 마운트한 다음 LikeButton 을 클릭한다.
  // hook 의 `mutation.isPending` 이 LikeButton 의 `disabled` / `aria-busy` 분기로
  // 흐르는지 페이지 컨텍스트에서 확인.
  describe("시나리오 A — pending 상태 reflect (disabled + aria-busy)", () => {
    it("LikeButton: 클릭 직후 disabled + aria-busy=true, BE 응답 후 해제", async () => {
      const user = userEvent.setup();
      const def = deferred<{ liked: boolean; songId: number }>();
      toggleLikeMock.mockImplementation(() => def.promise);

      renderWithQueryClient(<LikesPage />);

      // 페이지가 GET 응답 받고 SongCard 마운트 완료 — 좋아요 페이지 진입 시
      // store 동기화로 곡 #1 이 이미 liked 상태가 된다 (`/likes` 페이지의 정의).
      const button = await screen.findByRole("button", {
        name: /좋아요-곡-1 좋아요 취소/,
      });
      expect(button).not.toBeDisabled();
      expect(button).toHaveAttribute("aria-busy", "false");

      await user.click(button);

      await waitFor(() => {
        expect(button).toBeDisabled();
      });
      expect(button).toHaveAttribute("aria-busy", "true");

      await act(async () => {
        // 좋아요 취소 응답 — liked=false 로 store 보정 후 isPending 해제.
        def.resolve({ liked: false, songId: 1 });
        await Promise.resolve();
      });

      await waitFor(() => {
        expect(
          screen.getByRole("button", { name: /좋아요-곡-1 좋아요$/ }),
        ).not.toBeDisabled();
      });
      expect(
        screen.getByRole("button", { name: /좋아요-곡-1 좋아요$/ }),
      ).toHaveAttribute("aria-busy", "false");
    });

    it("BookmarkButton: 클릭 직후 disabled + aria-busy=true, BE 응답 후 해제", async () => {
      const user = userEvent.setup();
      const def = deferred<{ bookmarked: boolean; songId: number }>();
      toggleBookmarkMock.mockImplementation(() => def.promise);

      renderWithQueryClient(<LikesPage />);

      // /likes 페이지는 bookmark store 는 별도 — 초기 상태는 bookmarked=false.
      const button = await screen.findByRole("button", {
        name: /좋아요-곡-1 북마크$/,
      });
      expect(button).not.toBeDisabled();
      expect(button).toHaveAttribute("aria-busy", "false");

      await user.click(button);

      await waitFor(() => {
        expect(button).toBeDisabled();
      });
      expect(button).toHaveAttribute("aria-busy", "true");

      await act(async () => {
        def.resolve({ bookmarked: true, songId: 1 });
        await Promise.resolve();
      });

      await waitFor(() => {
        expect(
          screen.getByRole("button", { name: /좋아요-곡-1 북마크 해제/ }),
        ).not.toBeDisabled();
      });
      expect(
        screen.getByRole("button", { name: /좋아요-곡-1 북마크 해제/ }),
      ).toHaveAttribute("aria-busy", "false");
    });
  });

  // 시나리오 B — 401 ApiError → 한국어 alert (aria-live=assertive) reflect
  //
  // hook 의 onError 분기로 흘러 setErrorMessage 가 setMessage 되고 LikeButton 의
  // `errorMessage ? <p role="alert" aria-live="assertive">` 분기가 reflect 되는지
  // 페이지 컨텍스트에서 확인. PR #989 가 SongCard 직접 마운트로 cover 한 동일 가드를
  // /likes 페이지 흐름에서 다시 한 번 검증해 store wiring drift 회귀를 차단한다.
  describe("시나리오 B — 401 ApiError 시 alert 노출", () => {
    it("LikeButton: 401 ApiError → 좋아요 한국어 안내 alert + aria-live=assertive", async () => {
      const user = userEvent.setup();
      toggleLikeMock.mockRejectedValueOnce(unauthorizedError());

      renderWithQueryClient(<LikesPage />);

      const button = await screen.findByRole("button", {
        name: /좋아요-곡-1 좋아요 취소/,
      });
      await user.click(button);

      const alert = await screen.findByRole("alert");
      expect(alert).toHaveTextContent(/좋아요 처리에 실패했어요/);
      expect(alert).toHaveAttribute("aria-live", "assertive");
    });

    it("BookmarkButton: 401 ApiError → 북마크 한국어 안내 alert + aria-live=assertive", async () => {
      const user = userEvent.setup();
      toggleBookmarkMock.mockRejectedValueOnce(unauthorizedError());

      renderWithQueryClient(<LikesPage />);

      const button = await screen.findByRole("button", {
        name: /좋아요-곡-1 북마크$/,
      });
      await user.click(button);

      const alert = await screen.findByRole("alert");
      expect(alert).toHaveTextContent(/북마크 처리에 실패했어요/);
      expect(alert).toHaveAttribute("aria-live", "assertive");
    });
  });

  // 시나리오 C — 정상 toggle → optimistic UI 즉시 update
  //
  // mutationFn pending 중에도 (BE 응답 도착 전에) aria-pressed 가 즉시 토글되어야
  // 한다. hook 의 onMutate (낙관 토글) 가 zustand store → LikeButton 의
  // `aria-pressed={liked}` 분기로 page 컨텍스트에서 reflect 되는지 확인.
  describe("시나리오 C — 정상 toggle 낙관 UI reflect (aria-pressed)", () => {
    it("LikeButton: 좋아요 취소 클릭 → BE 응답 대기 중에도 aria-pressed=true→false 즉시 reflect", async () => {
      const user = userEvent.setup();
      const def = deferred<{ liked: boolean; songId: number }>();
      toggleLikeMock.mockImplementation(() => def.promise);

      renderWithQueryClient(<LikesPage />);

      // /likes 페이지가 store 에 곡 #1 을 push 했으니 초기 aria-pressed=true.
      const button = await screen.findByRole("button", {
        name: /좋아요-곡-1 좋아요 취소/,
      });
      expect(button).toHaveAttribute("aria-pressed", "true");

      await user.click(button);

      // BE pending 중에도 onMutate 낙관 토글이 store 반영 → aria-pressed=false.
      await waitFor(() => {
        expect(
          screen.getByRole("button", { name: /좋아요-곡-1 좋아요$/ }),
        ).toHaveAttribute("aria-pressed", "false");
      });

      await act(async () => {
        def.resolve({ liked: false, songId: 1 });
        await Promise.resolve();
      });
    });

    it("BookmarkButton: 북마크 클릭 → BE 응답 대기 중에도 aria-pressed=false→true 즉시 reflect", async () => {
      const user = userEvent.setup();
      const def = deferred<{ bookmarked: boolean; songId: number }>();
      toggleBookmarkMock.mockImplementation(() => def.promise);

      renderWithQueryClient(<LikesPage />);

      const button = await screen.findByRole("button", {
        name: /좋아요-곡-1 북마크$/,
      });
      expect(button).toHaveAttribute("aria-pressed", "false");

      await user.click(button);

      await waitFor(() => {
        expect(
          screen.getByRole("button", { name: /좋아요-곡-1 북마크 해제/ }),
        ).toHaveAttribute("aria-pressed", "true");
      });

      await act(async () => {
        def.resolve({ bookmarked: true, songId: 1 });
        await Promise.resolve();
      });
    });
  });
});
