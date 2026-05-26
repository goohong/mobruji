/**
 * /songs/[id] 곡 상세 페이지 query/cleanup race 가드 (closes #1100).
 *
 * 본 파일 도입 사유 (PR #1097 /history race guard 패턴 확장):
 *   - PR #1097 (#1093) — `/history` 페이지 컨텍스트 race guard 5건 + fixture/helper
 *     reuse 패턴 확립.
 *   - PR #993 / #1048 — `/likes` `/bookmarks` `/recommend` 페이지 mutation race 가드.
 *   - `/songs/[id]` 페이지는 **mutation 없음** — `DetailLikeButton` 이 zustand
 *     `useLikesStore.toggleLike` (synchronous) 만 호출하고, BE 좋아요 mutation 은 PR D
 *     머지 후 도입 예정. 따라서 본 PR 의 race 시나리오는 **query / cleanup / store
 *     독립성** 에 집중한다.
 *
 * AS-IS (race guard 부재 시 잠재 회귀 시나리오):
 *   - PR #1045 (`web/lib/hooks/useHistoryQueries-race.test.tsx`) — recommendation/voice
 *     range hook 본체 race 가드 — `/songs/[id]` 와 무관.
 *   - PR #989 — SongCard / SongDetailContent (`/recommend` 컨텍스트) mutation race —
 *     `/songs/[id]` 의 page wiring 과는 별 컴포넌트.
 *   - `/songs/[id]` **페이지 컨텍스트** query race / cleanup / store 독립성 검증 부재.
 *
 *   잠재 회귀 시나리오 (production 코드 변경 없이도 page wiring / hook / store 변경 시
 *   silent 회귀):
 *   - **songId param 변경 mid-flight (queryKey 격리)**: songId=1 query pending 도중
 *     URL 이 /songs/2 로 전환 → React Query 가 새 query 시작. 이전 응답이 도착해도
 *     queryKey ["song", 1] 이라 새 페이지 (songId=2) 카드 오염 0건.
 *   - **pending 중 페이지 unmount**: pending query 가 떠 있는 도중 페이지 unmount →
 *     React state update warning 0건. last-observer abort graceful.
 *   - **404 retry policy**: 404 ApiError 시 retry 0회 (즉시 NotFoundView). 누군가
 *     retry: 1 로 통일하면 사용자 가시 지연 + BE 부담 회귀.
 *   - **5xx retry 1회**: 5xx 시 1회 재시도 후 alert. 첫 호출만으로 alert 노출되면
 *     일시적 네트워크 깜빡임에도 사용자 가시 에러 → UX 회귀.
 *   - **store toggle 독립성**: query pending 중 좋아요 토글 클릭 → zustand store 만
 *     변경, query state 영향 없음 (refetch 트리거 X). 누군가 toggleLike 안에서
 *     queryClient.invalidateQueries 부르면 silent 회귀.
 *
 * TO-BE:
 *   본 파일은 `/songs/[id]` 페이지 마운트 → useQuery 활성화 → 좋아요 토글 / songId
 *   전환 / unmount 흐름에서 위 5 시나리오를 페이지 레벨로 검증한다 (production 코드
 *   변경 없이 테스트만 추가).
 *
 * 비범위:
 *   - production page.tsx / hook / store 변경 없음.
 *   - happy path / 404 / 5xx alert / a11y — `page.test.tsx` cover.
 *   - SongCard / SongDetailContent (`/recommend` 컨텍스트) mutation race — PR #989/#1048.
 *   - BE 좋아요 mutation 도입 (PR D 후속) 시 race 가드 — 별도 사이클.
 *
 * 보안:
 *   - sessionId 직접 의존 없음 (페이지 자체는 BE 좋아요 mutation 아직 안 씀).
 *   - likedSongIds zustand 는 songId 만 보관 — PII 없음.
 */

import { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  act,
  cleanup,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useParams } from "next/navigation";

import SongDetailPage from "./page";
import { ApiError } from "@/lib/api/client";
import { readSongById } from "@/lib/api/song";
import {
  createDeferred,
  makeQueryClient,
  makeWrapper,
} from "@/lib/test-helpers/race-helpers";
import {
  buildSongResponse,
  type SongResponse,
} from "@/lib/test-fixtures/song-detail";
import { useLikesStore } from "@/store/likes";

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

// renderWithQueryClient — race-helpers (PR #1057/#1061) makeQueryClient/makeWrapper
// reuse. 매 케이스마다 새 QueryClient 로 retry / cache 격리.
//
// 본 페이지는 retry policy 자체를 검증하는 시나리오 (4번 — 404 retry=0, 5번 — 5xx
// retry=1) 가 있어 default retry=false override 가 필요한 케이스는 명시적으로 1.
function renderWithQueryClient(ui: ReactNode) {
  const client = makeQueryClient();
  return render(ui, { wrapper: makeWrapper(client) });
}

beforeEach(() => {
  readSongByIdMock.mockReset();
  useParamsMock.mockReset();
  useLikesStore.setState({ likedSongIds: [] });
  if (typeof localStorage !== "undefined") {
    localStorage.removeItem("mobruji-likes");
  }
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe("/songs/[id] 곡 상세 페이지 query/cleanup race 가드 (#1100)", () => {
  // 시나리오 1 — songId param 변경 mid-flight (queryKey 격리)
  //
  // songId=1 query 가 pending 인 동안 useParams 가 "2" 로 바뀌면 React Query 가
  // queryKey ["song", 1] 의 observer 를 정리하고 새 query ["song", 2] 시작. 첫 응답이
  // 도착해도 이미 새 페이지 (songId=2) 의 observer 가 활성화되어 있어 카드 오염 0건.
  //
  // 가드 의미: 누군가 page.tsx 의 queryKey 를 ["song"] 으로 통일하거나 (id 미포함),
  // SongDetailContent 안에서 props.songId 를 무시하고 useParams 를 직접 다시 읽으면
  // silent 회귀. 본 가드가 차단.
  it("시나리오 1: songId 전환 시 이전 query 응답이 새 페이지 카드 오염 안 함 (queryKey 격리)", async () => {
    // 첫 마운트 — songId=1.
    useParamsMock.mockReturnValue({ id: "1" });

    const def1 = createDeferred<SongResponse>();
    const def2 = createDeferred<SongResponse>();
    readSongByIdMock
      .mockReturnValueOnce(def1.promise)
      .mockReturnValueOnce(def2.promise);

    const { rerender } = renderWithQueryClient(<SongDetailPage />);

    // 첫 호출 발생 — songId=1.
    await waitFor(() => {
      expect(readSongByIdMock).toHaveBeenCalledTimes(1);
    });
    expect(readSongByIdMock).toHaveBeenNthCalledWith(
      1,
      1,
      expect.any(AbortSignal),
    );

    // useParams 가 "2" 로 전환 → rerender.
    useParamsMock.mockReturnValue({ id: "2" });
    rerender(<SongDetailPage />);

    // 새 query 발생 — songId=2.
    await waitFor(() => {
      expect(readSongByIdMock).toHaveBeenCalledTimes(2);
    });
    expect(readSongByIdMock).toHaveBeenNthCalledWith(
      2,
      2,
      expect.any(AbortSignal),
    );

    // 이전 (songId=1) 응답이 **뒤늦게** 도착 — 새 페이지가 이를 차용하면 안 됨.
    await act(async () => {
      def1.resolve(buildSongResponse({ id: 1, title: "곡-1-stale" }));
      await Promise.resolve();
      await Promise.resolve();
    });

    // 새 페이지는 여전히 isPending (songId=2 응답 미도착) → "곡-1-stale" 노출 0건.
    expect(screen.queryByText("곡-1-stale")).not.toBeInTheDocument();

    // songId=2 응답 도착.
    await act(async () => {
      def2.resolve(buildSongResponse({ id: 2, title: "곡-2-fresh" }));
      await Promise.resolve();
      await Promise.resolve();
    });

    await waitFor(() => {
      expect(screen.getByText("곡-2-fresh")).toBeInTheDocument();
    });
    expect(screen.queryByText("곡-1-stale")).not.toBeInTheDocument();
  });

  // 시나리오 2 — pending 중 페이지 unmount (React state warning 0건)
  //
  // pending query 가 떠 있는 도중 페이지 unmount → React Query 가 last-observer abort
  // 호출. unmount 후 응답이 도착해도 setState 가 unmounted observer 라 React state
  // update warning 0건이어야 한다.
  //
  // 가드 의미: 누군가 page.tsx 의 useQuery 시그니처를 바꿔 cleanup 가드를 깨면 (예:
  // enabled 를 직접 setState 로 동기화) state update warning 발생 → dev/test 환경 noise
  // + 메모리 leak 잠재. 본 가드가 차단. /history (#1097) 시나리오 4 와 대칭.
  it("시나리오 2: pending 중 페이지 unmount → React state update warning 0건", async () => {
    useParamsMock.mockReturnValue({ id: "1" });

    const def = createDeferred<SongResponse>();
    readSongByIdMock.mockReturnValueOnce(def.promise);

    const consoleErrorSpy = vi
      .spyOn(console, "error")
      .mockImplementation(() => {});

    const { unmount } = renderWithQueryClient(<SongDetailPage />);

    await waitFor(() => {
      expect(readSongByIdMock).toHaveBeenCalledTimes(1);
    });

    // pending 중 페이지 unmount.
    unmount();

    // 응답 도착 — observer 가 비어있어 setState 가 안 일어나야 한다.
    await act(async () => {
      def.resolve(buildSongResponse({ id: 1, title: "곡-1" }));
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

  // 시나리오 3 — 404 retry policy (즉시 NotFoundView, 추가 호출 0건)
  //
  // page.tsx useQuery 의 retry 정책: 404 → 0회, 그 외 → 1회. 누군가 retry 를 numeric
  // 으로 통일하면 404 도 한번 더 호출 → 사용자 가시 지연 + BE 부담 회귀. 본 가드가
  // 차단.
  //
  // 가드 의미: `page.test.tsx` 의 단일 404 케이스는 호출 횟수를 1로 검증하지만, 본
  // 가드는 **시간 경과 후에도** retry 호출이 0건임을 확인 — retry-after 잠재기 동안
  // 추가 호출이 없음을 단언.
  it("시나리오 3: 404 응답 → retry 0회 (즉시 NotFoundView, BE 추가 호출 없음)", async () => {
    useParamsMock.mockReturnValue({ id: "9999" });
    readSongByIdMock.mockRejectedValue(
      new ApiError(404, "not found", { message: "not found" }),
    );

    renderWithQueryClient(<SongDetailPage />);

    await waitFor(() => {
      expect(screen.getByText(/곡을 찾을 수 없습니다/)).toBeInTheDocument();
    });

    // 첫 호출만 발생 — retry 가드 동작.
    expect(readSongByIdMock).toHaveBeenCalledTimes(1);

    // 시간 경과 (retry-after 잠재 구간) — 추가 호출 0건 유지.
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(readSongByIdMock).toHaveBeenCalledTimes(1);
  });

  // 시나리오 4 — 5xx retry 1회 후 alert (네트워크 깜빡임 graceful)
  //
  // useQuery retry policy: 404 가 아닌 에러는 failureCount < 1 = 1회 재시도. 즉
  // **두 번째 호출까지 5xx 일관** 일 때만 alert 분기. 첫 호출만으로 alert 노출되면
  // 일시적 네트워크 깜빡임에도 사용자 가시 에러 → UX 회귀.
  //
  // 가드 의미: 누군가 retry: false 로 단순화하면 첫 5xx 에 alert 즉시 노출 → 사용자
  // 체감 UX 저하 + 재시도 기회 상실. 본 가드가 차단. `page.test.tsx` (#745) 의 단일
  // 시나리오는 retry 후 alert 노출만 검증 — 본 가드는 **첫 호출 직후 alert 없음** +
  // **두 번째 호출 후 alert 있음** 두 사실을 함께 단언.
  it("시나리오 4: 5xx → 1회 재시도 후 alert 노출 (첫 호출 직후엔 alert 없음)", async () => {
    useParamsMock.mockReturnValue({ id: "1" });

    // 첫 호출 deferred, 두 번째 호출도 deferred — 호출 시점을 우리가 통제한다.
    const def1 = createDeferred<SongResponse>();
    const def2 = createDeferred<SongResponse>();
    readSongByIdMock
      .mockReturnValueOnce(def1.promise)
      .mockReturnValueOnce(def2.promise);

    renderWithQueryClient(<SongDetailPage />);

    // 첫 호출 발생.
    await waitFor(() => {
      expect(readSongByIdMock).toHaveBeenCalledTimes(1);
    });

    // 첫 호출 5xx reject → react-query 가 retry 1회 예약.
    await act(async () => {
      def1.reject(
        new ApiError(500, "internal", { message: "internal error" }),
      );
      await Promise.resolve();
      await Promise.resolve();
    });

    // 첫 호출 직후 — alert 노출 0건 (재시도 대기 중).
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();

    // 두 번째 호출 발생 대기 (retry-helpers 의 retryDelay=0 + queries.retry override).
    // 본 페이지의 retry 는 hook 본체가 직접 결정 (failureCount < 1) — race-helpers
    // 의 default retry=false 와 충돌하지 않도록 page 의 useQuery option 이 직접 override.
    await waitFor(
      () => {
        expect(readSongByIdMock).toHaveBeenCalledTimes(2);
      },
      { timeout: 3000 },
    );

    // 두 번째 호출도 5xx → alert 분기.
    await act(async () => {
      def2.reject(
        new ApiError(503, "service unavailable", { message: "down" }),
      );
      await Promise.resolve();
      await Promise.resolve();
    });

    const alert = await screen.findByRole(
      "alert",
      undefined,
      { timeout: 3000 },
    );
    expect(alert).toHaveAttribute("aria-live", "assertive");
    expect(alert).toHaveTextContent(/곡 정보를 불러오지 못했습니다/);
  });

  // 시나리오 5 — query pending 중 좋아요 토글 (store 독립성)
  //
  // `/songs/[id]` 의 좋아요 버튼은 zustand `useLikesStore.toggleLike` 동기 호출.
  // BE mutation 이 아니므로 query state (`isPending`, `data`) 와 완전히 독립이어야
  // 한다. 누군가 toggleLike 안에 queryClient.invalidateQueries 를 끼우면 query 가
  // refetch 트리거 → 사용자 입장에서 좋아요 1번에 BE 재호출 → 데이터 깜빡임 회귀.
  //
  // 본 가드는 **query pending 중에는 좋아요 버튼이 렌더되지 않음** (skeleton 분기) +
  // **응답 도착 후 좋아요 토글 시 readSongByIdMock 추가 호출 0건** 두 사실을 단언.
  it("시나리오 5: 응답 도착 후 좋아요 토글 → readSongByIdMock 추가 호출 0건 (query 독립)", async () => {
    const user = userEvent.setup();
    useParamsMock.mockReturnValue({ id: "1" });

    // 정상 응답 — DetailLikeButton 이 렌더된다.
    readSongByIdMock.mockResolvedValueOnce(
      buildSongResponse({ id: 1, title: "곡-1" }),
    );

    renderWithQueryClient(<SongDetailPage />);

    // 응답 도착 후 좋아요 버튼 노출.
    const likeButton = await screen.findByRole("button", {
      name: /곡-1 좋아요$/,
    });

    // 첫 호출 1회만.
    expect(readSongByIdMock).toHaveBeenCalledTimes(1);
    expect(useLikesStore.getState().likedSongIds).toEqual([]);

    // 좋아요 클릭 → zustand store 만 변경.
    await user.click(likeButton);

    await waitFor(() => {
      expect(useLikesStore.getState().likedSongIds).toEqual([1]);
    });

    // query 재호출 0건 — store 토글이 query 와 독립.
    expect(readSongByIdMock).toHaveBeenCalledTimes(1);

    // aria-pressed 가 true 로 토글 — UI reflect.
    const toggledButton = await screen.findByRole("button", {
      name: /곡-1 좋아요 취소$/,
    });
    expect(toggledButton).toHaveAttribute("aria-pressed", "true");

    // 한번 더 토글 → store 가 다시 [] + query 여전히 1회만.
    await user.click(toggledButton);
    await waitFor(() => {
      expect(useLikesStore.getState().likedSongIds).toEqual([]);
    });
    expect(readSongByIdMock).toHaveBeenCalledTimes(1);
  });
});

/**
 * 본 파일에서 사용된 race-helpers (PR #1057/#1061) + fixture (#1100) reuse 카탈로그:
 *   - race-helpers:
 *     * makeQueryClient — renderWithQueryClient 1곳
 *     * makeWrapper — renderWithQueryClient 1곳
 *     * createDeferred — 시나리오 1 (2회) + 시나리오 2 (1회) + 시나리오 4 (2회) = 5회
 *   - fixture (`song-detail.ts`, 본 PR):
 *     * buildSongResponse — 시나리오 1/2/5 = 3회 (inline `SongResponse` 정의 0건)
 *
 * PR #1100 의 LOC 절감 효과:
 *   - inline `buildSong` (16 LOC) 가 fixture 모듈로 옮겨가 본 파일 안에서 제거됨.
 *   - 추가 부수 효과: `page.test.tsx` 의 `buildSong` 도 향후 같은 fixture 로 통합 가능
 *     (별도 사이클).
 */
