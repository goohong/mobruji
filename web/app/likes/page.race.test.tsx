/**
 * /likes 페이지 mutation race 가드 (PR #993 후속, PR #1048 패턴 확장).
 *
 * AS-IS:
 *   - PR #985 가 `useLikeToggleMutation` / `useBookmarkToggleMutation` **단일 song**
 *     기준 race / unmount / 401 hook 가드 6건 추가.
 *   - PR #989 가 SongCard / SongDetailContent **직접 마운트** 환경 disabled +
 *     aria-busy + 401 alert + optimistic UI 4 컴포넌트 × 3 시나리오 = 16 통합 가드.
 *   - PR #993 가 `/likes` `/bookmarks` **페이지 컨텍스트** Button UI reflect 가드 6건.
 *   - PR #1048 가 `/recommend` 페이지 useInfiniteQuery race / 401 / unmount 7건.
 *   - 그러나 `/likes` `/bookmarks` 페이지에 **여러 song 카드가 동시 마운트** 된 상태에서
 *     서로 다른 songId 의 mutation 응답이 교차 도달하거나 (stale closure / cross
 *     contamination), 같은 songId 의 double-click 이 페이지 컨텍스트에서 BE 호출 1회로
 *     수렴하거나, optimistic update 후 BE 실패 시 zustand persist (`mobruji-likes`
 *     localStorage key) 가 롤백된 상태로 복구되는지는 페이지 레벨에서 검증 부재.
 *
 *   잠재 회귀 시나리오 (production 코드 변경 없이도 누군가 hook/store/page wiring 을
 *   변경하면 silent 회귀):
 *   - **stale closure / cross contamination**: 같은 페이지의 song A toggle 응답이
 *     song B 의 store 항목으로 잘못 매핑 → `mobruji-likes` localStorage 오염.
 *   - **double-click within page**: SongCard 한 장의 LikeButton 을 0.1초 안에 두 번
 *     클릭해도 `toggleLike` API 는 1회만 — hook 의 `isPending` 가드가 페이지
 *     컨텍스트에서 살아 있는지 확인.
 *   - **unmount mid-mutation at page level**: pending 도중 페이지 전체 unmount →
 *     onSuccess/onError 의 store / queryClient.invalidateQueries 가 React state
 *     update warning 0건.
 *   - **zustand persist race**: optimistic update 후 BE 401 → 롤백 시 zustand
 *     `mobruji-likes` localStorage 가 실제로 원상 복구 (낙관 토글 직전 상태).
 *   - **여러 song 동시 pending**: song A pending 중 song B 클릭 — 두 mutation 이
 *     서로 독립적으로 진행, 응답 도착 시 각자 자기 songId 만 보정.
 *
 * TO-BE:
 *   본 파일은 `/likes` 페이지 마운트 → 다수 SongCard 마운트 → toggle 클릭 흐름에서
 *   위 5 시나리오를 페이지 레벨로 검증한다 (production 코드 변경 없이 테스트만 추가).
 *
 * 비범위:
 *   - production 코드 (page.tsx / hook / store / SongCard) 변경 없음.
 *   - hook 자체 race / unmount / 401 흐름 — PR #985 cover.
 *   - SongCard 직접 마운트 button UI reflect — PR #989 cover.
 *   - 페이지 컨텍스트 single-song button UI reflect — PR #993 cover.
 *   - /bookmarks 동일 가드 — `web/app/bookmarks/page.race.test.tsx` 에서 대칭 cover.
 *   - happy path (빈 상태 / N건 렌더 / store 동기화) — `page.test.tsx` cover.
 *
 * 보안:
 *   - sessionId 는 PII (logging.ts §SENSITIVE_KEYS). 본 테스트의 "test-session-id"
 *     는 의도된 더미값.
 *   - localStorage 키 `mobruji-likes` 는 songId 만 보관 (PII 없음).
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
  type LikeToggleResponse,
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

// safeLog.error 가 onError 안에서 콘솔로 빠지면 unmount 케이스의 console.error spy
// 와 noise 가 섞인다. 본 파일 범위는 페이지/hook 동작이라 logging 형식은 mute.
vi.mock("@/lib/logging", () => ({
  safeLog: {
    error: vi.fn(),
    warn: vi.fn(),
    info: vi.fn(),
  },
}));

import { toggleLike } from "@/lib/api/feedback";

const readLikesMock = vi.mocked(readLikesBySessionId);
const toggleLikeMock = vi.mocked(toggleLike);

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
 * mutationFn pending 도중 응답 도착 시점을 외부에서 제어할 수 있게 한다.
 * race / unmount 케이스의 핵심 도구 (PR #985 hook 테스트 / PR #989 통합 / PR #1048
 * recommend race 와 동일 형태).
 */
function deferred<T>(): {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (reason: unknown) => void;
} {
  let resolveFn!: (value: T) => void;
  let rejectFn!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolveFn = res;
    rejectFn = rej;
  });
  return { promise, resolve: resolveFn, reject: rejectFn };
}

function unauthorizedError(): ApiError {
  return new ApiError(401, "Unauthorized", { error: "UNAUTHORIZED" });
}

beforeEach(() => {
  readLikesMock.mockReset();
  toggleLikeMock.mockReset();
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
  vi.useRealTimers();
});

describe("/likes 페이지 mutation race 가드 (PR #993 후속, PR #1048 패턴 확장)", () => {
  // 시나리오 1 — stale closure / cross contamination
  //
  // 페이지에 song #1, #2 가 모두 liked 로 마운트된 상태에서 song #1 을 먼저 클릭(취소
  // pending), 이어서 song #2 를 클릭한다. 두 deferred 응답을 의도적으로 **역순** 으로
  // 도착시켜 — song #2 응답이 song #1 응답보다 먼저 onSuccess 에 도달 — 각 mutation 의
  // onSuccess closure 가 자기 songId 로 store 를 보정하는지 검증. hook 본체의
  // `useLikeToggleMutation(songId)` 가 매 SongCard 마다 별 인스턴스를 만들기에 stale
  // closure 사고는 없어야 하지만, 누군가 single shared mutation 으로 리팩터링하면
  // silent 회귀 — 그 회귀를 차단한다.
  it("시나리오 1: 서로 다른 song 의 mutation 응답이 역순 도착해도 각자 자기 songId 만 보정", async () => {
    const user = userEvent.setup();
    readLikesMock.mockResolvedValue(buildWrapper([1, 2]));

    // song #1 / #2 각각 별 deferred — 응답 순서를 우리가 정한다.
    const def1 = deferred<LikeToggleResponse>();
    const def2 = deferred<LikeToggleResponse>();
    toggleLikeMock
      .mockReturnValueOnce(def1.promise)
      .mockReturnValueOnce(def2.promise);

    renderWithQueryClient(<LikesPage />);

    const button1 = await screen.findByRole("button", {
      name: /좋아요-곡-1 좋아요 취소/,
    });
    const button2 = await screen.findByRole("button", {
      name: /좋아요-곡-2 좋아요 취소/,
    });

    // 초기 상태 — 둘 다 liked (page 가 store 동기화).
    expect(useLikesStore.getState().likedSongIds).toEqual([1, 2]);

    // song #1 클릭 → 낙관 토글: store 에서 1 제거.
    await user.click(button1);
    await waitFor(() => {
      expect(useLikesStore.getState().likedSongIds).toEqual([2]);
    });
    expect(toggleLikeMock).toHaveBeenCalledTimes(1);

    // song #2 클릭 → 낙관 토글: store 에서 2 제거.
    await user.click(button2);
    await waitFor(() => {
      expect(useLikesStore.getState().likedSongIds).toEqual([]);
    });
    expect(toggleLikeMock).toHaveBeenCalledTimes(2);

    // **역순 도착** — song #2 응답 먼저 (liked=false, 낙관값과 일치 → 추가 토글 없음).
    await act(async () => {
      def2.resolve({ liked: false, songId: 2 });
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(useLikesStore.getState().likedSongIds).toEqual([]);

    // song #1 응답 도착 (liked=false, 낙관값과 일치).
    await act(async () => {
      def1.resolve({ liked: false, songId: 1 });
      await Promise.resolve();
      await Promise.resolve();
    });

    // 각 mutation 이 자기 songId 만 처리 — cross contamination 없음.
    expect(useLikesStore.getState().likedSongIds).toEqual([]);
    expect(toggleLikeMock).toHaveBeenCalledTimes(2);

    // toggleLikeMock 호출 인자가 각각 자기 songId 인지 검증.
    const calls = toggleLikeMock.mock.calls;
    const calledSongIds = calls.map(([req]) => req.songId).sort();
    expect(calledSongIds).toEqual([1, 2]);
  });

  // 시나리오 2 — double-click within page (BE 호출 1회 수렴)
  //
  // hook 의 `isPending` 가드 (`useFeedbackToggleMutation.ts:121`) 가 페이지 컨텍스트에서
  // 살아 있는지 확인. PR #985 hook 테스트가 단일 hook 인스턴스로 검증했지만, 페이지가
  // SongCard 를 마운트한 환경에서 클릭 핸들러 → hook → API 가 끊김없이 흐르는지는
  // 페이지 레벨 검증 부재. 누군가 SongCard 의 onClick 을 throttle 로 바꾸거나 hook 의
  // isPending 가드를 제거하면 silent 회귀.
  it("시나리오 2: 같은 song 의 빠른 더블 클릭 → toggleLike 1회만 호출 (isPending 가드)", async () => {
    const user = userEvent.setup();
    readLikesMock.mockResolvedValue(buildWrapper([1]));

    const def = deferred<LikeToggleResponse>();
    toggleLikeMock.mockReturnValueOnce(def.promise);

    renderWithQueryClient(<LikesPage />);

    const button = await screen.findByRole("button", {
      name: /좋아요-곡-1 좋아요 취소/,
    });

    // 첫 클릭 → pending 진입.
    await user.click(button);
    await waitFor(() => {
      expect(toggleLikeMock).toHaveBeenCalledTimes(1);
    });

    // pending 도중 한 번 더 클릭. hook 의 `if (mutation.isPending) return` 으로
    // 차단되어야 한다. 페이지가 disabled 속성을 SongCard 로 전달하므로 user-event 도
    // pointer-events: none 경로에 막힐 수 있지만, 그 자체가 가드의 핵심.
    await user.click(button).catch(() => {
      // user-event 가 disabled 버튼 클릭 시 throw 할 수 있음 — 핵심은 BE 호출 0건 추가.
    });
    expect(toggleLikeMock).toHaveBeenCalledTimes(1);

    // 응답 도착 — pending 해제.
    await act(async () => {
      def.resolve({ liked: false, songId: 1 });
      await Promise.resolve();
    });
    expect(toggleLikeMock).toHaveBeenCalledTimes(1);
  });

  // 시나리오 3 — 401 → alert + 낙관 롤백 (페이지 컨텍스트)
  //
  // hook 의 onError 가 store 를 다시 토글 (롤백) + 한국어 안내 메시지를 LikeButton 의
  // alert 분기로 흘리는지 페이지 컨텍스트에서 확인. PR #993 PageButtonReflect 가
  // 단일 song 으로 cover 했지만, 다수 카드가 마운트된 페이지에서 동일 alert 분기가
  // 자기 카드에만 노출되는지 (다른 카드 오염 없음) 페이지 레벨 검증 부재.
  it("시나리오 3: 401 응답 → 해당 song 카드에만 alert 노출 + store 롤백 (다른 카드 오염 없음)", async () => {
    const user = userEvent.setup();
    readLikesMock.mockResolvedValue(buildWrapper([1, 2]));
    toggleLikeMock.mockRejectedValueOnce(unauthorizedError());

    renderWithQueryClient(<LikesPage />);

    const button1 = await screen.findByRole("button", {
      name: /좋아요-곡-1 좋아요 취소/,
    });
    // 초기 store: [1, 2].
    expect(useLikesStore.getState().likedSongIds).toEqual([1, 2]);

    await user.click(button1);

    // alert 가 노출되고 한국어 안내 + aria-live=assertive.
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(/좋아요 처리에 실패했어요/);
    expect(alert).toHaveAttribute("aria-live", "assertive");

    // alert 는 정확히 1건만 — song #2 카드는 오염 안 됨.
    expect(screen.getAllByRole("alert")).toHaveLength(1);

    // store 롤백 — song #1 이 다시 liked. song #2 도 그대로.
    await waitFor(() => {
      expect(useLikesStore.getState().likedSongIds).toContain(1);
      expect(useLikesStore.getState().likedSongIds).toContain(2);
    });
  });

  // 시나리오 4 — unmount mid-mutation (페이지 전체)
  //
  // hook 의 mutationFn pending 도중 페이지가 unmount 되면 onSuccess 안의
  // queryClient.invalidateQueries / setMessage 가 unmounted observer 라도 React state
  // update warning 0건이어야 한다. PR #985 가 hook 단독으로 cover 했지만, 페이지가
  // 마운트한 다수 SongCard + LikesPage 의 useEffect (store sync) 가 함께 unmount 될 때
  // cleanup 흐름은 페이지 레벨 검증 부재.
  it("시나리오 4: pending 중 페이지 unmount → React state update warning 0건", async () => {
    const user = userEvent.setup();
    readLikesMock.mockResolvedValue(buildWrapper([1]));

    const def = deferred<LikeToggleResponse>();
    toggleLikeMock.mockReturnValueOnce(def.promise);

    const consoleErrorSpy = vi
      .spyOn(console, "error")
      .mockImplementation(() => {});

    const { unmount } = renderWithQueryClient(<LikesPage />);

    const button = await screen.findByRole("button", {
      name: /좋아요-곡-1 좋아요 취소/,
    });
    await user.click(button);
    await waitFor(() => {
      expect(toggleLikeMock).toHaveBeenCalledTimes(1);
    });

    // mutation pending 중 페이지 전체 unmount.
    unmount();

    // 응답 도착 — onSuccess 안의 setState/store/queryClient 접근이 unmounted
    // 라도 경고 없이 종료되어야 한다.
    await act(async () => {
      def.resolve({ liked: false, songId: 1 });
      await Promise.resolve();
      await Promise.resolve();
    });

    const warningCalls = consoleErrorSpy.mock.calls.filter((args) => {
      const message = typeof args[0] === "string" ? args[0] : "";
      return (
        message.includes("unmounted component") ||
        message.includes("memory leak") ||
        message.includes("state update on an unmounted")
      );
    });
    expect(warningCalls).toEqual([]);

    consoleErrorSpy.mockRestore();
  });

  // 시나리오 5 — zustand persist race (optimistic + BE 실패 → localStorage 롤백)
  //
  // hook onMutate 가 store toggle → zustand persist 가 localStorage `mobruji-likes`
  // 에 즉시 sync. BE 401 도착 시 onError 의 두 번째 toggle 이 다시 발화해야 하고,
  // localStorage 도 원상 복구 되어야 한다. persist 미들웨어가 set() 마다 동기 sync
  // 하므로 (createJSONStorage(localStorage) 사용), 마지막 store 상태와 localStorage
  // 내용이 일치해야 한다. 누군가 persist key 를 바꾸거나 hook 의 onError 롤백을
  // 제거하면 silent 회귀 — 그 회귀를 차단한다.
  it("시나리오 5: optimistic + BE 실패 → store 롤백 + localStorage `mobruji-likes` 정합", async () => {
    const user = userEvent.setup();
    readLikesMock.mockResolvedValue(buildWrapper([1]));
    toggleLikeMock.mockRejectedValueOnce(unauthorizedError());

    renderWithQueryClient(<LikesPage />);

    const button = await screen.findByRole("button", {
      name: /좋아요-곡-1 좋아요 취소/,
    });

    // 초기 store [1] — page sync.
    expect(useLikesStore.getState().likedSongIds).toEqual([1]);

    await user.click(button);

    // 401 → onError 발화 → 롤백.
    await waitFor(() => {
      expect(useLikesStore.getState().likedSongIds).toEqual([1]);
    });

    // localStorage `mobruji-likes` 가 최종 store 상태와 정합.
    // (zustand persist 가 set() 마다 동기 sync — 마지막 set 이 롤백 toggle 이므로
    //  localStorage 도 원상 복구되어야 한다.)
    const persisted = localStorage.getItem("mobruji-likes");
    expect(persisted).not.toBeNull();
    const parsed = JSON.parse(persisted as string) as {
      state: { likedSongIds: number[] };
    };
    expect(parsed.state.likedSongIds).toEqual([1]);
  });
});
