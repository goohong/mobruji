/**
 * useLikeToggleMutation / useBookmarkToggleMutation 경계 회귀 가드
 * (PR #979 `useAutoDismissMessage` 4건 후속).
 *
 * 검증 범위 (각 hook 별 동일 3 케이스):
 *
 *   1) 빠른 연속 클릭 race condition
 *      - 동일 turn 안에 `toggle()` 을 2회 연속 호출해도 BE 호출은 1회만.
 *      - hook 본체의 `if (mutation.isPending) return;` 가드 회귀 차단.
 *      - 의도: 사용자가 0.1초 안에 더블 클릭해도 BE 멱등 토글이 두 번 호출되지
 *        않도록 한다. 본 가드가 깨지면 좋아요 상태가 의도와 반대로 정착.
 *
 *   2) unmount 중간에 mutation 응답 도착 시 setState 무시
 *      - mutationFn pending 도중 `unmount()`. resolve 시 React warning
 *        ("Can't perform a React state update on an unmounted component")
 *        없이 정상 종료해야 한다.
 *      - React Query 가 unmounted observer 의 setState 를 무시하는지 회귀 확인.
 *      - happy-dom + console.error spy 로 확인.
 *
 *   3) auth 401 응답 시 error message + auto-dismiss 연계
 *      - 401 `ApiError` 가 mutationFn 에서 reject 되면
 *        a) zustand store 가 낙관 토글 → 롤백되어 원상 복귀
 *        b) `errorMessage` 가 한국어 안내 문자열로 세팅
 *        c) `INTERACTION_FEEDBACK_DURATION_MS` (3000ms) 경과 시 null 로 자동 정리
 *      - PR #979 의 `useAutoDismissMessage` 통합이 본 hook 에서도 살아 있는지 회귀 확인.
 *
 * 비범위 (의도적으로 검증 안 함):
 *   - hook 본체 변경 (본 PR 은 테스트만 추가)
 *   - api/feedback 모듈 자체 동작
 *   - SongCard / SongDetailContent 컴포넌트 통합 (별도 component test 책임)
 */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";

import { ApiError } from "@/lib/api/client";
import {
  createDeferred,
  makeQueryClient,
  makeWrapper,
} from "@/lib/test-helpers/race-helpers";
import { useLikesStore } from "@/store/likes";
import { useBookmarksStore } from "@/store/bookmarks";
import { useSessionStore } from "@/store/session";

// 본 hook 이 의존하는 API/logging 모듈을 vi.mock 으로 격리.
// 실제 fetch 가 일어나면 happy-dom 환경에서 unhandled rejection 이 떠 race 검증이
// 흐려진다. 또한 401 케이스에서 진짜 네트워크 호출이 일어나면 wall-clock 이
// 들쭉날쭉해서 fake timer 와 결합이 어려워진다.
vi.mock("@/lib/api/feedback", () => ({
  toggleLike: vi.fn(),
  toggleBookmark: vi.fn(),
}));

// safeLog.error 가 onError 안에서 콘솔로 빠지면 console.error spy (unmount 케이스용)
// 와 noise 가 섞인다. 본 테스트 범위는 hook 동작이지 logging 형식이 아니므로 mute.
vi.mock("@/lib/logging", () => ({
  safeLog: {
    error: vi.fn(),
    warn: vi.fn(),
    info: vi.fn(),
  },
}));

import { toggleLike, toggleBookmark } from "@/lib/api/feedback";
import {
  INTERACTION_FEEDBACK_DURATION_MS,
  useBookmarkToggleMutation,
  useLikeToggleMutation,
} from "./useFeedbackToggleMutation";

const toggleLikeMock = vi.mocked(toggleLike);
const toggleBookmarkMock = vi.mocked(toggleBookmark);

const TEST_SONG_ID = 4242;

// QueryClient / Wrapper / Deferred helper 는 `@/lib/test-helpers/race-helpers` 단일
// 소스 (PR #1057 refactor) — 기본 default 가 queries/mutations 양쪽 retry=false +
// retryDelay=0 이라 401 케이스에서 onError 가 즉시 트리거된다.

beforeEach(() => {
  vi.useFakeTimers();
  // store 들은 persist 미들웨어를 쓰므로 case 간 잔존 상태 격리.
  useLikesStore.setState({ likedSongIds: [] });
  useBookmarksStore.setState({ bookmarkedSongIds: [] });
  useSessionStore.setState({
    sessionId: "00000000-0000-4000-8000-000000000001",
    voiceRangeId: null,
    excludedSongIds: [],
  });
  if (typeof localStorage !== "undefined") {
    localStorage.removeItem("mobruji-likes");
    localStorage.removeItem("mobruji-bookmarks");
    localStorage.removeItem("mobruji-session");
  }
});

afterEach(() => {
  vi.useRealTimers();
  vi.clearAllMocks();
});

describe("useLikeToggleMutation 경계 가드", () => {
  it("빠른 연속 클릭 시 BE 호출은 1회만 발생한다 (isPending 가드)", async () => {
    const deferred = createDeferred<{ liked: boolean; songId: number }>();
    toggleLikeMock.mockReturnValueOnce(deferred.promise);

    const client = makeQueryClient();
    const { result } = renderHook(() => useLikeToggleMutation(TEST_SONG_ID), {
      wrapper: makeWrapper(client),
    });

    // 0ms 안에 사용자가 2회 클릭한 상황 모사. 두 번째 호출은 isPending 가드로
    // 차단되어야 한다. React Query 의 `mutate` 는 mutationFn 을 microtask 로
    // 스케줄하므로 첫 호출 직후 한 번 flush 한 뒤 두 번째 호출을 수행해야
    // hook 의 `isPending` 가드 (직전 호출 진행 중인지) 가 실제로 발화한다.
    await act(async () => {
      result.current.toggle();
      // mutationFn dispatch microtask flush — toggleLikeMock 가 실제로 호출되며
      // 동시에 isPending=true 가 hook 본체에 반영된다.
      await Promise.resolve();
    });

    expect(toggleLikeMock).toHaveBeenCalledTimes(1);
    expect(result.current.isPending).toBe(true);

    // 같은 turn 안에 사용자가 한 번 더 클릭 — 가드로 차단되어야 한다.
    act(() => {
      result.current.toggle();
    });
    expect(toggleLikeMock).toHaveBeenCalledTimes(1);

    // 첫 호출 응답을 도착시켜 cleanup. 응답 `liked=true` 는 낙관값과 일치하므로
    // 추가 토글 없음.
    await act(async () => {
      deferred.resolve({ liked: true, songId: TEST_SONG_ID });
      await deferred.promise;
    });

    expect(toggleLikeMock).toHaveBeenCalledTimes(1);
    expect(useLikesStore.getState().likedSongIds).toEqual([TEST_SONG_ID]);
  });

  it("unmount 직후 응답이 도착해도 React state update warning 없이 종료된다", async () => {
    const deferred = createDeferred<{ liked: boolean; songId: number }>();
    toggleLikeMock.mockReturnValueOnce(deferred.promise);

    const consoleErrorSpy = vi
      .spyOn(console, "error")
      .mockImplementation(() => {});

    const client = makeQueryClient();
    const { result, unmount } = renderHook(
      () => useLikeToggleMutation(TEST_SONG_ID),
      { wrapper: makeWrapper(client) },
    );

    await act(async () => {
      result.current.toggle();
      await Promise.resolve();
    });
    expect(toggleLikeMock).toHaveBeenCalledTimes(1);

    // mutation pending 중 컴포넌트 unmount.
    unmount();

    // 그 후 응답 도착 — onSuccess 안의 setState/store 접근이 unmounted observer
    // 라도 React 경고 없이 종료되어야 한다.
    await act(async () => {
      deferred.resolve({ liked: true, songId: TEST_SONG_ID });
      await deferred.promise;
    });

    // "Can't perform a React state update on an unmounted component" 류
    // 경고가 한 줄도 없어야 한다.
    const warningCalls = consoleErrorSpy.mock.calls.filter((args) => {
      const message = typeof args[0] === "string" ? args[0] : "";
      return (
        message.includes("unmounted component") ||
        message.includes("memory leak")
      );
    });
    expect(warningCalls).toEqual([]);

    consoleErrorSpy.mockRestore();
  });

  it("401 응답 시 store 롤백 + 에러 메시지 세팅 + 3초 후 자동 dismiss", async () => {
    const deferred = createDeferred<{ liked: boolean; songId: number }>();
    toggleLikeMock.mockReturnValueOnce(deferred.promise);

    const client = makeQueryClient();
    const { result } = renderHook(() => useLikeToggleMutation(TEST_SONG_ID), {
      wrapper: makeWrapper(client),
    });

    expect(result.current.liked).toBe(false);

    act(() => {
      result.current.toggle();
    });
    // onMutate 가 낙관 토글 완료 — 잠깐 liked=true.
    expect(useLikesStore.getState().likedSongIds).toEqual([TEST_SONG_ID]);

    // 401 reject → onError 발화.
    await act(async () => {
      deferred.reject(new ApiError(401, "Unauthorized", { error: "auth" }));
      // mutation observer 가 reject 를 처리하도록 microtask flush.
      await Promise.resolve();
      await Promise.resolve();
    });

    // 롤백 — 다시 false.
    expect(useLikesStore.getState().likedSongIds).toEqual([]);
    expect(result.current.errorMessage).toBe(
      "좋아요 처리에 실패했어요. 다시 시도해 주세요.",
    );

    // INTERACTION_FEEDBACK_DURATION_MS(3000ms) 경과 시 자동 정리.
    act(() => {
      vi.advanceTimersByTime(INTERACTION_FEEDBACK_DURATION_MS);
    });
    expect(result.current.errorMessage).toBeNull();
  });
});

describe("useBookmarkToggleMutation 경계 가드", () => {
  it("빠른 연속 클릭 시 BE 호출은 1회만 발생한다 (isPending 가드)", async () => {
    const deferred = createDeferred<{ bookmarked: boolean; songId: number }>();
    toggleBookmarkMock.mockReturnValueOnce(deferred.promise);

    const client = makeQueryClient();
    const { result } = renderHook(
      () => useBookmarkToggleMutation(TEST_SONG_ID),
      { wrapper: makeWrapper(client) },
    );

    await act(async () => {
      result.current.toggle();
      await Promise.resolve();
    });

    expect(toggleBookmarkMock).toHaveBeenCalledTimes(1);
    expect(result.current.isPending).toBe(true);

    act(() => {
      result.current.toggle();
    });
    expect(toggleBookmarkMock).toHaveBeenCalledTimes(1);

    await act(async () => {
      deferred.resolve({ bookmarked: true, songId: TEST_SONG_ID });
      await deferred.promise;
    });

    expect(toggleBookmarkMock).toHaveBeenCalledTimes(1);
    expect(useBookmarksStore.getState().bookmarkedSongIds).toEqual([
      TEST_SONG_ID,
    ]);
  });

  it("unmount 직후 응답이 도착해도 React state update warning 없이 종료된다", async () => {
    const deferred = createDeferred<{ bookmarked: boolean; songId: number }>();
    toggleBookmarkMock.mockReturnValueOnce(deferred.promise);

    const consoleErrorSpy = vi
      .spyOn(console, "error")
      .mockImplementation(() => {});

    const client = makeQueryClient();
    const { result, unmount } = renderHook(
      () => useBookmarkToggleMutation(TEST_SONG_ID),
      { wrapper: makeWrapper(client) },
    );

    await act(async () => {
      result.current.toggle();
      await Promise.resolve();
    });
    expect(toggleBookmarkMock).toHaveBeenCalledTimes(1);

    unmount();

    await act(async () => {
      deferred.resolve({ bookmarked: true, songId: TEST_SONG_ID });
      await deferred.promise;
    });

    const warningCalls = consoleErrorSpy.mock.calls.filter((args) => {
      const message = typeof args[0] === "string" ? args[0] : "";
      return (
        message.includes("unmounted component") ||
        message.includes("memory leak")
      );
    });
    expect(warningCalls).toEqual([]);

    consoleErrorSpy.mockRestore();
  });

  it("401 응답 시 store 롤백 + 에러 메시지 세팅 + 3초 후 자동 dismiss", async () => {
    const deferred = createDeferred<{ bookmarked: boolean; songId: number }>();
    toggleBookmarkMock.mockReturnValueOnce(deferred.promise);

    const client = makeQueryClient();
    const { result } = renderHook(
      () => useBookmarkToggleMutation(TEST_SONG_ID),
      { wrapper: makeWrapper(client) },
    );

    expect(result.current.bookmarked).toBe(false);

    act(() => {
      result.current.toggle();
    });
    expect(useBookmarksStore.getState().bookmarkedSongIds).toEqual([
      TEST_SONG_ID,
    ]);

    await act(async () => {
      deferred.reject(new ApiError(401, "Unauthorized", { error: "auth" }));
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(useBookmarksStore.getState().bookmarkedSongIds).toEqual([]);
    expect(result.current.errorMessage).toBe(
      "북마크 처리에 실패했어요. 다시 시도해 주세요.",
    );

    act(() => {
      vi.advanceTimersByTime(INTERACTION_FEEDBACK_DURATION_MS);
    });
    expect(result.current.errorMessage).toBeNull();
  });
});
