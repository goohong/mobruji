/**
 * `/songs` 검색 페이지 query/cleanup race 가드 (closes #1100 후속).
 *
 * 본 파일 도입 사유 (PR #1103 `/songs/[id]` race guard 패턴 확장):
 *   - PR #1103 (#1100) — `/songs/[id]` 페이지 컨텍스트 race guard 5건 + fixture/helper
 *     reuse 패턴 확립.
 *   - PR #1097 (#1093) — `/history` 페이지 컨텍스트 race guard 5건.
 *   - PR #993 / #1048 — `/likes` `/bookmarks` `/recommend` 페이지 mutation race 가드.
 *   - `/songs` 검색 페이지는 **mutation 없음** + **debounce 통과 keyword 변경** 이
 *     query lifecycle 의 핵심 — 본 PR 의 race 시나리오는 **keyword race / abort /
 *     cleanup / 401 graceful** 에 집중한다.
 *
 * AS-IS (race guard 부재 시 잠재 회귀 시나리오):
 *   - `page.test.tsx` (위 본문 8건 + a11y 3건) 는 happy path / 필터 / URL 동기화 / 단일
 *     500 alert 만 검증 — debounce / abort / cleanup / 401 보정 race 검증 부재.
 *   - PR #1100 (`/songs/[id]`) 의 시나리오 5건 은 query 1건 (`readSongById`) 단순
 *     lifecycle — `/songs` 의 keyword race + debounce 합성은 별 시나리오.
 *
 *   잠재 회귀 시나리오 (production 코드 변경 없이도 page wiring / hook / queryKey 변경
 *   시 silent 회귀):
 *   - **keyword race (cross-contamination)**: keyword=A pending 도중 사용자가 keyword
 *     를 B 로 갈아끼움 (debounce 통과). 두 query 가 동시에 in-flight → 두 응답 순서가
 *     뒤바껴 도착해도 화면엔 마지막 keyword (B) 응답만 노출. queryKey ["songs",
 *     "search", keyword] 격리 가 핵심.
 *   - **빈 keyword 즉시 enabled=false**: keyword 입력 후 사용자가 전부 지움 →
 *     enabled=false → 새 query 생략 + (이전 keyword 응답이 도착해도 marked stale —
 *     debouncedKeyword 가 빈 string 으로 갈아끼움). 누군가 enabled 가드를 깨면
 *     사용자 의도와 어긋난 호출 (빈 keyword 로 search) + 사용자 가시 stale 결과 회귀.
 *   - **mid-flight unmount**: searchSongs pending 중 페이지 unmount → AbortSignal 발사
 *     + last-observer cleanup → setState 0건 → React state warning 0건.
 *   - **401 graceful (retry 0회)**: 401 ApiError → alert 노출 + retry 0건 (default
 *     retry=false). 누군가 retry: 1 default 로 통일하면 인증 만료 케이스에서 사용자
 *     가시 지연 + BE 부담 회귀.
 *   - **debounce 중 입력 폭주 (마지막 keyword 만 fetch)**: 사용자가 빠르게 "h" → "he"
 *     → "hel" → "hell" → "hello" 5타 입력 (debounce 300ms 미만 간격). 디바운스 통과
 *     keyword 만 호출 — 5번이 아니라 1번. 누군가 setTimeout 가드 를 깨면 BE 부담 회귀.
 *
 * TO-BE:
 *   본 파일은 `/songs` 페이지 마운트 → 입력 → 디바운스 통과 → useQuery 활성화 흐름
 *   에서 위 5 시나리오를 페이지 레벨로 검증한다 (production 코드 변경 없이 테스트만
 *   추가).
 *
 * 비범위:
 *   - production page.tsx / hook / queryKey 변경 없음.
 *   - happy path / 빈 응답 fallback / 필터 / URL 동기화 / a11y — `page.test.tsx` cover.
 *   - `/songs/[id]` 단건 페이지 race — PR #1103 cover.
 *   - 좋아요 mutation race (BE 도입 후) — 별도 사이클.
 *
 * 보안:
 *   - sessionId 직접 의존 없음 (검색 페이지 자체는 BE 좋아요 mutation 아직 안 씀).
 *   - keyword 는 URL query 에만 기록 — PII 없음 (사용자가 직접 입력).
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

import SongSearchPage from "./page";
import { ApiError } from "@/lib/api/client";
import { searchSongs } from "@/lib/api/song";
import {
  createDeferred,
  makeQueryClient,
  makeWrapper,
} from "@/lib/test-helpers/race-helpers";
import {
  buildSongList,
  type SongResponse,
} from "@/lib/test-fixtures/song-list";

// next/navigation 가짜 구현 — happy-dom 환경에서 next/navigation hook 이 동작하지 않음.
// paramsRef 로 테스트별 초기 query 주입 가능 — page.test.tsx 와 동일한 패턴.
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

// renderWithQueryClient — race-helpers (PR #1057/#1061) makeQueryClient/makeWrapper
// reuse. 매 케이스마다 새 QueryClient 로 retry / cache 격리.
//
// 본 페이지의 useQuery 는 page 내부에서 retry 옵션을 따로 지정하지 않아 race-helpers
// default (retry=false) 가 그대로 적용 — 401 / 5xx 시 retry 0회 가 본 race 가드의 의도.
function renderWithQueryClient(ui: ReactNode) {
  const client = makeQueryClient();
  return render(ui, { wrapper: makeWrapper(client) });
}

beforeEach(() => {
  searchSongsMock.mockReset();
  replaceMock.mockReset();
  paramsRef.current = new URLSearchParams();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe("/songs 검색 페이지 query/cleanup race 가드 (#1100 후속)", () => {
  // 시나리오 1 — keyword race (cross-contamination 차단)
  //
  // keyword=hello query pending 도중 사용자가 keyword 를 world 로 갈아끼움 (디바운스
  // 통과). 두 query 가 in-flight → 응답 순서가 뒤바껴 도착해도 화면에는 마지막 keyword
  // 의 응답만 노출. queryKey ["songs", "search", keyword] 격리 가 핵심.
  //
  // 가드 의미: 누군가 queryKey 를 ["songs", "search"] (keyword 미포함) 로 통일하거나,
  // page.tsx 에서 debouncedKeyword 대신 inputValue 를 직접 keyword 로 쓰면 (디바운스
  // 통과 후 setState 가 안 일어남) cross-contamination 회귀. 본 가드가 차단.
  it("시나리오 1: keyword 전환 시 이전 keyword 응답이 새 keyword 결과 오염 안 함 (queryKey 격리)", async () => {
    const user = userEvent.setup();

    const defHello = createDeferred<SongResponse[]>();
    const defWorld = createDeferred<SongResponse[]>();
    searchSongsMock
      .mockReturnValueOnce(defHello.promise)
      .mockReturnValueOnce(defWorld.promise);

    renderWithQueryClient(<SongSearchPage />);

    const input = screen.getByPlaceholderText("곡 제목이나 아티스트로 검색");

    // 첫 keyword "hello" 입력 → 디바운스 통과 → 첫 호출.
    await user.type(input, "hello");
    await waitFor(
      () => {
        expect(searchSongsMock).toHaveBeenCalledTimes(1);
      },
      { timeout: 2000 },
    );
    expect(searchSongsMock.mock.calls[0][0]).toBe("hello");

    // 사용자가 keyword 갈아끼움 — 기존 입력 전부 지우고 "world" 새로 입력.
    await user.clear(input);
    await user.type(input, "world");

    // 두 번째 호출 발생 대기 — keyword="world".
    await waitFor(
      () => {
        expect(searchSongsMock).toHaveBeenCalledTimes(2);
      },
      { timeout: 2000 },
    );
    expect(searchSongsMock.mock.calls[1][0]).toBe("world");

    // 이전 keyword (hello) 응답이 **뒤늦게** 도착 — 새 화면이 이를 차용하면 안 됨.
    await act(async () => {
      defHello.resolve(buildSongList("hello", 2, 100));
      await Promise.resolve();
      await Promise.resolve();
    });

    // 새 화면 (keyword="world") 은 아직 isPending → hello-1 카드 노출 0건.
    expect(screen.queryByText("hello-1")).not.toBeInTheDocument();
    expect(screen.queryByText("hello-2")).not.toBeInTheDocument();

    // world 응답 도착 — 마지막 keyword 의 결과만 화면에.
    await act(async () => {
      defWorld.resolve(buildSongList("world", 2, 200));
      await Promise.resolve();
      await Promise.resolve();
    });

    await waitFor(() => {
      expect(screen.getByText("world-1")).toBeInTheDocument();
    });
    expect(screen.getByText("world-2")).toBeInTheDocument();
    // hello 결과는 여전히 노출 0건.
    expect(screen.queryByText("hello-1")).not.toBeInTheDocument();
    expect(screen.queryByText("hello-2")).not.toBeInTheDocument();
  });

  // 시나리오 2 — 빈 keyword 즉시 enabled=false (이전 응답 stale 마킹)
  //
  // 사용자가 keyword 입력 후 전부 지움 → debouncedKeyword="" → enabled=false → 새 query
  // 호출 0건 + 화면이 "검색어를 입력해 보세요" 가이드 로 복귀.
  //
  // 가드 의미: 누군가 enabled 가드를 깨면 (예: enabled 를 항상 true 로 두면) 빈
  // keyword 로 BE 호출 발생 → 사용자 의도 어긋난 호출 + BE 부담. 본 가드가 차단.
  it("시나리오 2: keyword 모두 지우면 enabled=false → 새 호출 0건 + 가이드 복귀", async () => {
    const user = userEvent.setup();

    searchSongsMock.mockResolvedValueOnce(buildSongList("hello", 1, 100));

    renderWithQueryClient(<SongSearchPage />);

    const input = screen.getByPlaceholderText("곡 제목이나 아티스트로 검색");

    // 첫 keyword "hello" → 디바운스 통과 → 응답 도착.
    await user.type(input, "hello");
    await waitFor(
      () => {
        expect(searchSongsMock).toHaveBeenCalledTimes(1);
      },
      { timeout: 2000 },
    );
    await waitFor(() => {
      expect(screen.getByText("hello-1")).toBeInTheDocument();
    });

    // keyword 전부 지움 → debouncedKeyword="" → enabled=false.
    await user.clear(input);

    // 디바운스 통과 후에도 추가 호출 0건 + 가이드 복귀.
    await waitFor(
      () => {
        expect(
          screen.getByText(/검색어를 입력해 보세요/),
        ).toBeInTheDocument();
      },
      { timeout: 2000 },
    );

    // hello 호출 1건만 — 빈 keyword 로 추가 호출 안 발생.
    expect(searchSongsMock).toHaveBeenCalledTimes(1);
    // 이전 결과 카드는 가이드 분기로 가려져 노출 0건.
    expect(screen.queryByText("hello-1")).not.toBeInTheDocument();
  });

  // 시나리오 3 — mid-flight unmount (React state warning 0건)
  //
  // searchSongs pending 도중 페이지 unmount → React Query 가 last-observer abort
  // 호출. unmount 후 응답이 도착해도 setState 가 unmounted observer 라 React state
  // update warning 0건이어야 한다.
  //
  // 가드 의미: 누군가 page.tsx 의 useQuery 시그니처를 바꿔 cleanup 가드를 깨면 (예:
  // enabled 를 직접 setState 로 동기화) state update warning 발생 → dev/test 환경
  // noise + 메모리 leak 잠재. 본 가드가 차단. /songs/[id] (#1100) 시나리오 2 와 대칭.
  it("시나리오 3: pending 중 페이지 unmount → React state update warning 0건", async () => {
    const user = userEvent.setup();

    const def = createDeferred<SongResponse[]>();
    searchSongsMock.mockReturnValueOnce(def.promise);

    const consoleErrorSpy = vi
      .spyOn(console, "error")
      .mockImplementation(() => {});

    const { unmount } = renderWithQueryClient(<SongSearchPage />);

    const input = screen.getByPlaceholderText("곡 제목이나 아티스트로 검색");
    await user.type(input, "hello");

    await waitFor(
      () => {
        expect(searchSongsMock).toHaveBeenCalledTimes(1);
      },
      { timeout: 2000 },
    );

    // pending 중 페이지 unmount.
    unmount();

    // 응답 도착 — observer 가 비어있어 setState 가 안 일어나야 한다.
    await act(async () => {
      def.resolve(buildSongList("hello", 1, 100));
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

  // 시나리오 4 — 401 graceful (retry 0회, alert 즉시 노출)
  //
  // page.tsx 의 useQuery 는 retry 옵션을 따로 지정하지 않아 makeQueryClient 의
  // default (retry=false) 가 적용된다. 401 ApiError → alert 즉시 노출 + retry 호출
  // 0건. 누군가 retry: 1 default 로 통일하면 인증 만료 케이스에서 사용자 가시 지연 +
  // BE 부담 회귀. 본 가드가 차단.
  //
  // 가드 의미: `page.test.tsx` 의 단일 500 alert 케이스는 SR 가시성 (role=alert) 만
  // 검증 — 본 가드는 **시간 경과 후 추가 호출 0건** + **401 메시지 텍스트 포함** 두
  // 사실을 함께 단언.
  it("시나리오 4: 401 응답 → alert 즉시 노출 + retry 0건 (graceful)", async () => {
    const user = userEvent.setup();

    searchSongsMock.mockRejectedValueOnce(
      new ApiError(401, "Unauthorized", { error: "UNAUTHORIZED" }),
    );

    renderWithQueryClient(<SongSearchPage />);

    const input = screen.getByPlaceholderText("곡 제목이나 아티스트로 검색");
    await user.type(input, "hello");

    const alert = await screen.findByRole(
      "alert",
      undefined,
      { timeout: 3000 },
    );
    expect(alert).toHaveAttribute("aria-live", "assertive");
    expect(alert).toHaveTextContent(/검색에 실패했습니다/);
    // ApiError 의 status 가 메시지에 포함 — 401 표기.
    expect(alert).toHaveTextContent("401");

    // 첫 호출 1건만 — retry 가드 동작.
    expect(searchSongsMock).toHaveBeenCalledTimes(1);

    // 시간 경과 (retry-after 잠재 구간) — 추가 호출 0건 유지.
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(searchSongsMock).toHaveBeenCalledTimes(1);
  });

  // 시나리오 5 — 디바운스 중 입력 폭주 → 마지막 keyword 만 fetch
  //
  // 사용자가 빠르게 "h" → "he" → "hel" → "hell" → "hello" 5타 입력. user.type 은
  // 기본적으로 키 사이 delay 없이 입력 → setTimeout(DEBOUNCE_MS=300) 가드 가 동작해
  // 5번 setDebouncedKeyword 호출이 마지막 1회로 수렴. searchSongs 도 1번만 호출.
  //
  // 가드 의미: 누군가 useEffect deps 변경 시 cleanup 가드 (clearTimeout) 를 깨면
  // 입력 N글자에 N번 BE 호출 회귀. 본 가드가 차단.
  it("시나리오 5: 디바운스 중 폭주 입력 → 마지막 keyword 만 단일 호출 (BE 부담 가드)", async () => {
    // user.type 은 default 로 키 사이 delay 가 미세하게 있다 — 0 으로 명시해 디바운스
    // 윈도우 안에 5타가 모두 들어가도록 강제.
    const user = userEvent.setup({ delay: null });

    searchSongsMock.mockResolvedValueOnce(buildSongList("hello", 1, 100));

    renderWithQueryClient(<SongSearchPage />);

    const input = screen.getByPlaceholderText("곡 제목이나 아티스트로 검색");
    // "hello" 한번에 type — userEvent 내부적으로 5타가 발생하지만 delay=null 로
    // 디바운스 윈도우(300ms) 안에 수렴.
    await user.type(input, "hello");

    // 디바운스 통과 + 호출 발생 대기.
    await waitFor(
      () => {
        expect(searchSongsMock).toHaveBeenCalled();
      },
      { timeout: 2000 },
    );

    // 응답 도착.
    await waitFor(() => {
      expect(screen.getByText("hello-1")).toBeInTheDocument();
    });

    // 디바운스 가드 동작 검증 — 5타가 1번 호출로 수렴.
    expect(searchSongsMock).toHaveBeenCalledTimes(1);
    expect(searchSongsMock.mock.calls[0][0]).toBe("hello");

    // 시간 경과 (디바운스 윈도우 가 끝나도) — 추가 호출 0건 유지.
    await new Promise((resolve) => setTimeout(resolve, 400));
    expect(searchSongsMock).toHaveBeenCalledTimes(1);
  });
});

/**
 * 본 파일에서 사용된 race-helpers (PR #1057/#1061) + fixture (#1100 후속) reuse 카탈로그:
 *   - race-helpers:
 *     * makeQueryClient — renderWithQueryClient 1곳
 *     * makeWrapper — renderWithQueryClient 1곳
 *     * createDeferred — 시나리오 1 (2회) + 시나리오 3 (1회) = 3회
 *   - fixture (`song-list.ts`, 본 PR):
 *     * buildSongList — 시나리오 1 (2회) + 시나리오 2 (1회) + 시나리오 3 (1회) +
 *       시나리오 5 (1회) = 5회 (inline `SongResponse[]` 정의 0건)
 *
 * PR #1100 후속 의 LOC 절감 효과:
 *   - inline `twoSongsResponse` (~30 LOC) 패턴이 fixture 모듈로 옮겨가 본 파일 안에서
 *     keyword 별 응답 셋업이 한 줄 builder 로 압축됨.
 *   - 추가 부수 효과: `page.test.tsx` 의 inline `SongResponse[]` 정의 도 향후 같은
 *     fixture 로 통합 가능 (별도 사이클).
 */
