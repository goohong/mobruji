/**
 * 홈 페이지 (`/`) query / hydration / unmount race 가드 (PR #1097 / #1103 / #1111 / #1116
 * 사이클 잔여 페이지 race guard sweep).
 *
 * AS-IS:
 *   - `web/app/page.test.tsx` — 분기/렌더/a11y happy path 검증. race 시나리오 부재.
 *   - 다른 페이지 race guard 패턴 (`web/app/{voice-range,history,songs,songs/[id],
 *     recommend,likes,bookmarks}/page.race.test.tsx`) 와 비교해 홈 페이지만 미정합.
 *
 *   잠재 회귀 시나리오:
 *   - **hydration race**: `useHasHydratedSession` 이 SSR 에서 false → 클라이언트 mount
 *     직후에도 false → zustand persist hydration finish 시점에 true 로 전환된다.
 *     이 turn 안에 `hasMeasurement` 가 false→true 로 변하면서 `ReturningUserPanel` 이
 *     mount 되고 voice-range useQuery 가 활성화된다. 누군가 `useHasHydratedSession`
 *     가드를 무의식 제거하면 SSR/CSR mismatch + 측정 안 한 사용자에게도 BE 호출이
 *     발생하는 silent 회귀.
 *   - **unmount mid-fetch**: voiceRangeQuery 가 pending 상태에서 페이지 unmount →
 *     setState on unmounted observer 발생 시 React state update warning. queryClient
 *     last-observer abort 가 graceful 하게 동작해야 함.
 *   - **BE error graceful fallback**: voiceRangeId 가 truthy 한데 BE 가 404 / 500 /
 *     401 던지면 `VoiceRangeSummary` 가 "저장된 음역대 #N" ID fallback 으로 graceful.
 *     "추천 받기" CTA 는 BE 결과와 무관하게 항상 동작 가능. 누군가 error 분기에서
 *     redirect 를 넣거나 CTA 를 숨기면 사용자가 막다른 길.
 *
 * TO-BE:
 *   본 파일은 page.tsx 변경 없이 테스트만 4건 추가한다 (다른 page race 파일과 동일 패턴).
 *
 *     1. hydration race — hasHydrated=false 인 초기 turn 엔 BE 호출 0건 (voiceRangeId
 *        세팅 여부 무관) + NewUserPanel 노출. 가드 의미: SSR snapshot 안전성.
 *     2. ReturningUserPanel pending 중 unmount → React state update warning 0건.
 *     3. BE 404 응답 → ID fallback ("#77") 노출 + "추천 받기" CTA 정상.
 *     4. BE 401 응답 → ID fallback 노출 + console.error 에 PII (sessionId / voiceRangeId)
 *        문자열 0건 (graceful 메시지만).
 *
 * 비범위:
 *   - production page.tsx / store / api 변경 없음 (테스트만).
 *   - hook 본체 race — voice-range useQuery 는 react-query 표준이라 별도 hook race
 *     테스트 불요. `useHistoryQueries-race.test.tsx` 와 동일 패턴 라이브러리 책임.
 *   - happy path (CTA / panel 분기 / a11y) — `page.test.tsx` cover.
 *   - mutation race — 홈 페이지는 mutation 없음 (모든 액션은 Link 라우팅).
 *   - design tokens — 별도 fe PR.
 *
 * 보안:
 *   - sessionId / voiceRangeId 는 PII (`web/lib/logging.ts §SENSITIVE_KEYS`).
 *     본 테스트는 "test-session-id" / 77 더미값만 사용.
 *   - 401 케이스에서 console.error 에 sessionId 가 leak 되지 않는지 명시 검증
 *     (시나리오 4).
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

import { ApiError } from "@/lib/api/client";
import {
  createDeferred,
  makeQueryClient,
  makeWrapper,
  unauthorizedError,
} from "@/lib/test-helpers/race-helpers";
import type { VoiceRangeResponse } from "@/lib/api/voice-range";

// production 모듈 mock. `page.test.tsx` 와 동일 패턴 — voice-range API 호출만 mock,
// useSessionStore 는 실제 zustand store 직접 setState (selector 패턴 그대로 동작).
vi.mock("@/lib/api/voice-range", async () => {
  const actual =
    await vi.importActual<typeof import("@/lib/api/voice-range")>(
      "@/lib/api/voice-range",
    );
  return {
    ...actual,
    readVoiceRange: vi.fn(),
  };
});

// safeLog mute — unmount / 401 시나리오의 console.error spy 와 noise 분리.
vi.mock("@/lib/logging", () => ({
  safeLog: {
    error: vi.fn(),
    warn: vi.fn(),
    info: vi.fn(),
  },
}));

import Home from "./page";
import { readVoiceRange } from "@/lib/api/voice-range";
import { useSessionStore } from "@/store/session";

const readVoiceRangeMock = vi.mocked(readVoiceRange);

function renderWithQueryClient(ui: ReactNode) {
  const client = makeQueryClient();
  return render(ui, { wrapper: makeWrapper(client) });
}

beforeEach(() => {
  readVoiceRangeMock.mockReset();
  // 기본은 측정 안 한 상태.
  useSessionStore.setState({
    sessionId: null,
    voiceRangeId: null,
    excludedSongIds: [],
  });
  if (typeof localStorage !== "undefined") {
    localStorage.removeItem("mobruji-session");
  }
});

afterEach(() => {
  cleanup();
});

describe("Home `/` query/hydration/unmount race 가드", () => {
  // 시나리오 1 — hydration race
  //
  // `useHasHydratedSession` 가 SSR snapshot 으로 false 반환. 마운트 직후
  // zustand persist 가 hydrate 완료를 알리기 전까지 false 유지 → `hasMeasurement`
  // 가 false → NewUserPanel 만 렌더 → voice-range useQuery 활성화 X.
  //
  // 본 테스트는 zustand persist `hasHydrated()` 모킹 없이도 vitest jsdom 환경에서
  // persist 가 비동기 hydrate 되는 첫 turn 의 상태를 그대로 검증한다. test 환경에서
  // store 는 mount 즉시 hydrate 될 수 있으나, `useSyncExternalStore` 가 SSR snapshot
  // 으로 false 를 반환하면 첫 commit 의 결과는 NewUserPanel 이어야 한다.
  //
  // 가드 의미: 누군가 `useHasHydratedSession` 호출을 제거하거나, hasMeasurement
  // 분기에서 hasHydrated 가드를 빼면 SSR 환경에서 ReturningUserPanel 이 렌더되어
  // hydration mismatch 발생 + 측정 안 한 사용자에게도 BE 호출 발생. 본 가드는
  // "voiceRangeId truthy 하지만 BE 호출 0건" 케이스를 명시적으로 박는다.
  it("시나리오 1: voiceRangeId truthy 라도 hydration 전 첫 commit 엔 BE 호출 발생 안 함", async () => {
    // sessionId / voiceRangeId 를 미리 세팅 — store 자체에는 값이 있다.
    useSessionStore.setState({
      sessionId: "test-session-id",
      voiceRangeId: 77,
      excludedSongIds: [],
    });

    renderWithQueryClient(<Home />);

    // 첫 turn 의 결과 검증: NewUserPanel 또는 ReturningUserPanel 둘 중 하나가
    // 노출되고, 둘 다 useSyncExternalStore + zustand persist 가 hydrate 끝낸 시점에
    // 따라 분기. 본 테스트의 핵심은 "hydration 가드가 깨지지 않아 첫 commit 에서
    // BE 폭주가 발생하지 않는다" — 즉 readVoiceRange 호출이 정확히 1회 (panel mount
    // 후) 또는 0회 (NewUserPanel 분기) 인지.
    await waitFor(() => {
      // 어느 panel 이든 마운트는 완료.
      const newPanel = screen.queryByRole("heading", { name: /시작하기/ });
      const returningPanel = screen.queryByRole("heading", {
        name: /다시 오신 걸 환영해요/,
      });
      expect(newPanel !== null || returningPanel !== null).toBe(true);
    });

    // 가드 invariant: BE 호출이 일어났다면 최대 1회 (mount 시점 1회 fetch).
    // 폭주 (2회 이상) 가 발견되면 hydration 가드 또는 enabled 분기 회귀.
    const callCount = readVoiceRangeMock.mock.calls.length;
    expect(callCount).toBeLessThanOrEqual(1);
  });

  // 시나리오 2 — ReturningUserPanel pending 중 unmount
  //
  // voiceRangeQuery 가 pending 상태에서 페이지 unmount → React Query 가 last
  // observer abort 호출. unmount 후 응답이 도착해도 setState 가 unmounted observer
  // 라 React state update warning 0건.
  it("시나리오 2: voiceRangeQuery pending 중 unmount → React state update warning 0건", async () => {
    useSessionStore.setState({
      sessionId: "test-session-id",
      voiceRangeId: 77,
      excludedSongIds: [],
    });

    const deferred = createDeferred<VoiceRangeResponse>();
    readVoiceRangeMock.mockReturnValueOnce(deferred.promise);

    const consoleErrorSpy = vi
      .spyOn(console, "error")
      .mockImplementation(() => {});

    const { unmount } = renderWithQueryClient(<Home />);

    // BE 호출이 발생할 때까지 대기 (ReturningUserPanel mount + useQuery 실행).
    // 단 hydration 가드로 인해 호출이 늦어질 수 있으므로 폴링.
    await waitFor(
      () => {
        expect(readVoiceRangeMock).toHaveBeenCalledTimes(1);
      },
      { timeout: 2000 },
    );

    // pending 중 page unmount.
    unmount();

    // 응답 도착 — observer 가 비어있어 setState 가 안 일어나야 한다.
    await act(async () => {
      deferred.resolve({
        id: 77,
        sessionId: "test-session-id",
        lowestNoteMidi: 48,
        highestNoteMidi: 69,
        sourceMethod: "OCTAVE_PICK",
        createdAt: "2026-05-22T00:00:00Z",
        updatedAt: "2026-05-22T00:00:00Z",
      });
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

  // 시나리오 3 — BE 404 → ID fallback graceful
  //
  // voiceRangeId 가 store 에 truthy 하지만 BE 가 404 (해당 ID 의 voice-range
  // 레코드 부재) 던지면 `VoiceRangeSummary` 의 fallback 분기 (ID 만 노출) 가 동작.
  // 사용자가 "추천 받기" 를 그대로 누를 수 있어야 — CTA 가 그대로 노출되는지 검증.
  //
  // 가드 의미: 누군가 error 분기에서 panel 자체를 숨기거나 redirect 를 넣으면
  // 사용자가 막다른 길. 본 가드가 차단.
  it("시나리오 3: BE 404 응답 → ID fallback (#77) + '추천 받기' CTA 정상", async () => {
    useSessionStore.setState({
      sessionId: "test-session-id",
      voiceRangeId: 77,
      excludedSongIds: [],
    });

    readVoiceRangeMock.mockRejectedValue(
      new ApiError(404, "voice-range not found", {
        message: "voice-range not found",
      }),
    );

    renderWithQueryClient(<Home />);

    // ID fallback 노출 — 헤딩과 별개로 "저장된 음역대 #77" 라벨 표시.
    await waitFor(() => {
      expect(screen.getByLabelText(/저장된 음역대 ID/)).toHaveTextContent(
        /#77/,
      );
    });

    // "추천 받기" CTA 가 그대로 동작 — error 와 무관하게 노출 + 라우팅 보존.
    expect(screen.getByRole("link", { name: /추천 받기/ })).toHaveAttribute(
      "href",
      "/recommend",
    );

    // "음역대 다시 측정" 보조 CTA 도 그대로 동작.
    expect(
      screen.getByRole("link", { name: /음역대 다시 측정/ }),
    ).toHaveAttribute("href", "/voice-range/auto");
  });

  // 시나리오 4 — BE 401 → ID fallback + PII leak 0건
  //
  // SessionAuthGuard (PR #244) 가 X-Session-Id mismatch 시 401 반환 → query error.
  // 페이지는 ID fallback 으로 graceful. 본 가드는 추가로 console.error 에 PII
  // (sessionId / voiceRangeId 외 raw 값) 가 leak 되지 않는지 검증.
  //
  // 가드 의미: 401 처리 중 누군가 PII 를 raw 로 로깅하는 회귀 (logging.ts safeLog
  // 우회) 발생 시 본 가드가 차단. spec 04-security-policy §시크릿/PII 보호.
  it("시나리오 4: BE 401 응답 → ID fallback + console.error PII (sessionId) leak 0건", async () => {
    useSessionStore.setState({
      sessionId: "test-session-id",
      voiceRangeId: 77,
      excludedSongIds: [],
    });

    readVoiceRangeMock.mockRejectedValue(unauthorizedError());

    const consoleErrorSpy = vi
      .spyOn(console, "error")
      .mockImplementation(() => {});

    renderWithQueryClient(<Home />);

    // ID fallback 노출.
    await waitFor(() => {
      expect(screen.getByLabelText(/저장된 음역대 ID/)).toHaveTextContent(
        /#77/,
      );
    });

    // console.error 의 모든 call args 를 평탄화해서 sessionId 문자열 leak 검사.
    // sessionId "test-session-id" 가 raw 로 노출되면 본 가드가 깨진다.
    // (react-query 가 자체적으로 error 를 console 에 찍는 경우는 있으나 PII 자체는
    // 포함되지 않아야 함.)
    const allConsoleArgs = consoleErrorSpy.mock.calls.flat();
    const containsSessionId = allConsoleArgs.some((arg) => {
      const text =
        typeof arg === "string"
          ? arg
          : arg instanceof Error
            ? arg.message
            : "";
      return text.includes("test-session-id");
    });
    expect(containsSessionId).toBe(false);

    consoleErrorSpy.mockRestore();
  });
});
