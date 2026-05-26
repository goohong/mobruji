/**
 * `/voice-range` 페이지 mutation/cleanup race 가드 (closes #1115).
 *
 * 본 파일 도입 사유 (PR #1111 `/songs` race guard 패턴 확장):
 *   - PR #985 (#1015 후속) — `page.test.tsx` 안에 race 가드 3건 (빠른 연속 클릭 /
 *     Button reflect / unmount warning) 이미 cover.
 *   - PR #1097 (`/history`) / #1103 (`/songs/[id]`) / #1111 (`/songs`) 가 페이지별
 *     `page.race.test.tsx` 분리 + race-helpers (PR #1057) reuse 패턴 확립.
 *   - `/voice-range` 페이지는 `useMutation(createVoiceRange)` + `useSessionStore` +
 *     `router.push("/recommend")` + `queryClient.setQueryData(["voice-range",
 *     sessionId])` 4 채널을 동시에 결합 — 본 PR 은 mutation 경로의 cross-contamination
 *     / 401 graceful / mid-flight unmount 부수효과 / navigation cancel cache 오염
 *     시나리오 5건 을 페이지 레벨로 추가.
 *
 * AS-IS (race guard 부재 시 잠재 회귀 시나리오):
 *   - `page.test.tsx` 의 PR #985 가드 3건 은 Button disabled / aria-busy / warning
 *     문자열 부재만 검증 — onSuccess 부수효과 (setVoiceRangeId / pushMock /
 *     setQueryData) 가 unmount 후 호출 0건 보장은 부재.
 *   - mutation race 시 cross-contamination (두 응답 도착 시 setVoiceRangeId 가 첫
 *     응답 ID 만 받는지) 검증 부재.
 *   - 401 응답 시 router.push 호출 0건 + retry 0건 + alert 메시지 텍스트 단언 부재.
 *
 *   잠재 회귀 시나리오 + 현재 동작 박제 (production 코드 변경 없이도 page wiring /
 *   hook / mutation onSuccess 콜백 변경 시 silent 회귀를 검출하기 위해 현재 동작을
 *   명시적으로 단언한다):
 *   - **mutation race (현재 동작 박제)**: Button disabled 가드가 깨져 두 mutate 가
 *     in-flight 가능해진 경우, `useMutation` 은 useQuery 와 달리 cancel-by-key 가드가
 *     없어 두 응답 모두 onSuccess 가 발화한다. 즉 setVoiceRangeId 가 2회 + router.push
 *     이 2회 호출된다. 본 시나리오는 이 race 동작 자체를 박제 — 본 단언이 깨지면
 *     mutationKey + cancelMutations 가드가 추가됐다는 의미.
 *   - **401 graceful**: ApiError(401) → submitError alert 즉시 노출 + retry 0건 +
 *     router.push("/recommend") 호출 0건 + setVoiceRangeId 호출 0건. 누군가
 *     useMutation 에 retry: 1 default 를 추가하면 인증 만료 케이스에서 사용자 가시
 *     지연 + BE 부담 회귀.
 *   - **mid-flight unmount (현재 동작 박제 + warning 0건)**: mutationFn pending 도중
 *     unmount → 응답 도착 시 React state warning 0건 (PR #985 reaffirm) +
 *     useMutation onSuccess 부수효과는 호출됨 (현재 동작 — useMutation 은
 *     unmount-aware 가 아님). 본 단언이 깨지면 mountedRef / signal 가드가 추가됐다는
 *     의미.
 *   - **측정 도중 navigation cancel (현재 동작 박제 — cache leak)**: unmount 후
 *     응답이 도착해도 setQueryData 가 호출돼 cache prime 됨. 다음 페이지가 같은
 *     sessionId 로 진입 시 cache 히트로 stale 응답 소비 가능. 본 단언이 깨지면
 *     AbortController / signal 가드가 추가됐다는 의미.
 *   - **빈 sessionId 가드 (defensive)**: ensureSessionId 가 fallback 으로 빈 문자열
 *     반환하는 사고 시 mutation 의 sessionId 헤더가 빈 string 으로 전달돼 BE 가
 *     401 처리. 본 케이스는 mutationFn 이 호출되어 sessionId="" 가 그대로 전달되는
 *     사실을 단언 (page.tsx 가 추가 가드를 안 한다는 점을 박제) — 가드 추가 시 본
 *     단언이 깨지면서 page.tsx 변경이 명시되도록 함.
 *
 * TO-BE:
 *   본 파일은 `/voice-range` 페이지 마운트 → 폼 제출 → mutation lifecycle 전반에서
 *   위 5 시나리오를 페이지 레벨로 검증한다 (production 코드 변경 없이 테스트만 추가).
 *
 * 비범위:
 *   - production page.tsx / useMutation 옵션 / API 변경 없음.
 *   - happy path / validation / a11y / 키보드 탐색 — `page.test.tsx` cover.
 *   - `/voice-range/auto` 페이지 (마이크 의존 흐름) — 별도 사이클.
 *   - PR #985 race 가드 3건 (빠른 연속 클릭 / Button reflect / unmount warning) —
 *     `page.test.tsx` cover 이미 됨, 본 파일은 더 강한 부수효과 단언으로 보강.
 *
 * 보안:
 *   - sessionId 직접 의존 (mutation 헤더) — fixture 의 `TEST_SESSION_ID` 만 사용.
 *   - PII 노출 / 시크릿 로그 0건.
 */

import { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QueryClientProvider } from "@tanstack/react-query";
import {
  act,
  cleanup,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import VoiceRangePage from "./page";
import { ApiError } from "@/lib/api/client";
import { createVoiceRange } from "@/lib/api/voice-range";
import {
  createDeferred,
  makeQueryClient,
} from "@/lib/test-helpers/race-helpers";
import {
  buildVoiceRangeResponse,
  TEST_SESSION_ID,
} from "@/lib/test-fixtures/voice-range";

// vi.hoisted — vi.mock factory 가 hoisting 되므로 mock 이 참조하는 식별자도 hoisting
// 되어야 한다. `page.test.tsx` 와 동일한 mock-session-store 패턴.
const { sessionMock, pushMock } = await vi.hoisted(async () => {
  const helper = await import("@/lib/test-helpers/mock-session-store");
  return {
    sessionMock: helper.buildSessionStoreMock(),
    pushMock: vi.fn(),
  };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock }),
}));

vi.mock("@/store/session", () => ({
  useSessionStore: sessionMock.useSessionStore,
}));

vi.mock("@/lib/api/voice-range", async () => {
  const actual =
    await vi.importActual<typeof import("@/lib/api/voice-range")>(
      "@/lib/api/voice-range",
    );
  return {
    ...actual,
    createVoiceRange: vi.fn(),
  };
});

const createVoiceRangeMock = vi.mocked(createVoiceRange);

/**
 * renderWithQueryClient — race-helpers (PR #1057/#1061) makeQueryClient reuse.
 *
 * `makeWrapper` 가 아닌 인라인 QueryClientProvider 를 쓴 이유:
 *   - 시나리오 4/5 가 setQueryData cache prime 부재를 검증하려면 client 인스턴스를
 *     테스트가 직접 접근해야 한다 — `page.test.tsx` 의 `renderWithExposedQueryClient`
 *     패턴을 race-helpers 의 makeQueryClient 위에 얹어 retry=false 가드를 공유.
 *
 * `makeQueryClient()` default 가 queries/mutations retry=false 이므로 401 시나리오
 * 가 단일 호출로 즉시 error state 로 수렴 — 본 파일 5 시나리오 모두 retry 0건 가드
 * 를 의도.
 */
function renderWithExposedQueryClient(ui: ReactNode) {
  const client = makeQueryClient();
  const rendered = render(
    <QueryClientProvider client={client}>{ui}</QueryClientProvider>,
  );
  return { ...rendered, client };
}

function renderWithQueryClient(ui: ReactNode) {
  const client = makeQueryClient();
  return render(
    <QueryClientProvider client={client}>{ui}</QueryClientProvider>,
  );
}

beforeEach(() => {
  sessionMock.reset();
  pushMock.mockReset();
  createVoiceRangeMock.mockReset();
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("/voice-range 페이지 mutation/cleanup race 가드 (#1111 후속)", () => {
  // 시나리오 1 — mutation race (현재 동작 박제)
  //
  // 사용자가 첫 mutation pending 도중 새 폼 값으로 두 번째 mutation 을 트리거 (Button
  // disabled 가드 회귀 시나리오를 모사 — `form.requestSubmit()` 으로 두 번째 mutation
  // 트리거).
  //
  // **현재 동작 박제**: `useMutation` 은 react-query observer 가 "마지막 mutate" 만
  // 추적하는 useQuery 와 달리, 각 mutate 호출의 onSuccess 가 응답 도착 순으로 모두 호출
  // 된다. 즉 두 응답이 도착하면 setVoiceRangeId 가 2회 호출되고 router.push 도 2회
  // 호출된다. 마지막 호출이 두 번째 응답 ID 면 사용자 가시 결과는 두 번째 응답 ID 와
  // 일치하지만, 첫 응답이 늦게 도착하면 setVoiceRangeId 가 stale ID 로 다시 호출돼
  // /recommend 페이지가 stale voiceRangeId 로 진입할 위험이 있다.
  //
  // 가드 의미: 본 테스트가 깨지면 다음 중 하나가 변한 것:
  //   (a) Button disabled 가드가 깨져 두 mutate 가 in-flight 가능해진 경우.
  //   (b) useMutation 옵션에 mutationKey + cancelMutations 가 추가돼 race 가 차단된
  //       경우 — 이 경우 본 시나리오의 단언이 깨지면서 개선이 명시된다.
  //
  // 박제 사실:
  //   - createVoiceRange 가 2회 호출됨 (Button disabled 가드 우회 시).
  //   - setVoiceRangeId 가 2회 호출됨 (두 응답 모두 onSuccess 발화).
  //   - 마지막 setVoiceRangeId 호출은 늦게 도착한 응답의 ID 임 (현재 race 동작 박제).
  it("시나리오 1: Button disabled 가드 우회 시 두 mutate 의 onSuccess 가 모두 호출된다 (race 박제)", async () => {
    const user = userEvent.setup();

    const defFirst = createDeferred<ReturnType<typeof buildVoiceRangeResponse>>();
    const defSecond =
      createDeferred<ReturnType<typeof buildVoiceRangeResponse>>();
    createVoiceRangeMock
      .mockReturnValueOnce(defFirst.promise)
      .mockReturnValueOnce(defSecond.promise);

    renderWithQueryClient(<VoiceRangePage />);

    const submit = screen.getByRole("button", { name: /추천 받기/ });

    // 첫 mutation 트리거.
    await user.click(submit);
    await waitFor(() => {
      expect(createVoiceRangeMock).toHaveBeenCalledTimes(1);
    });

    // Button disabled 가드 회귀를 모사 — Button disabled 가 깨졌다고 가정하고
    // 폼을 직접 submit 으로 두 번째 mutation 트리거. user.click 이 아닌
    // `form.requestSubmit()` 직접 호출로 disabled prop 회피.
    const form = submit.closest("form");
    expect(form).toBeTruthy();
    await act(async () => {
      form!.requestSubmit();
    });

    await waitFor(() => {
      expect(createVoiceRangeMock).toHaveBeenCalledTimes(2);
    });

    // 두 번째 응답 먼저 도착 → setVoiceRangeId(200) 1회 호출.
    await act(async () => {
      defSecond.resolve(buildVoiceRangeResponse({ id: 200 }));
      await defSecond.promise;
    });

    await waitFor(() => {
      expect(sessionMock.state().setVoiceRangeId).toHaveBeenCalledWith(200);
    });

    // 첫 응답이 뒤늦게 도착 → useMutation 은 cancel 가드 없어 setVoiceRangeId(100)
    // 도 호출됨 (현재 동작 박제).
    await act(async () => {
      defFirst.resolve(buildVoiceRangeResponse({ id: 100 }));
      await defFirst.promise;
    });

    await new Promise((resolve) => setTimeout(resolve, 50));

    // setVoiceRangeId 는 2회 호출 — 두 응답 모두 onSuccess 발화.
    const calls = sessionMock.state().setVoiceRangeId as unknown as {
      mock: { calls: unknown[][] };
    };
    const calledIds = calls.mock.calls.map((args) => args[0]);
    expect(calledIds).toHaveLength(2);
    // 두 ID 모두 포함 (호출 순서는 application code 가 보장하지 않음 — set 단언).
    expect(new Set(calledIds)).toEqual(new Set([100, 200]));

    // router.push 도 두 번 호출됨 (각 onSuccess 가 라우팅 시도) — race 동작 박제.
    expect(pushMock).toHaveBeenCalledTimes(2);
    expect(pushMock).toHaveBeenCalledWith("/recommend");
  });

  // 시나리오 2 — 401 graceful (retry 0건 + push 호출 0건)
  //
  // ApiError(401) → submitError alert 즉시 노출 + retry 0건 + router.push("/recommend")
  // 호출 0건 + setVoiceRangeId 호출 0건. `page.test.tsx` 의 500 케이스는 alert 문자열
  // 검증만 — 본 가드는 router.push / setVoiceRangeId / retry 호출 0건 단언 추가.
  //
  // 가드 의미: 누군가 useMutation 에 retry: 1 default 를 추가하거나 onError 에서
  // router.push 를 호출하도록 잘못 바꾸면 인증 만료 케이스에서 사용자 가시 지연 +
  // 비의도 라우팅 회귀. 본 가드가 차단.
  it("시나리오 2: 401 응답 → alert 즉시 노출 + retry 0건 + router.push / setVoiceRangeId 0건", async () => {
    const user = userEvent.setup();

    createVoiceRangeMock.mockRejectedValueOnce(
      new ApiError(401, "Unauthorized", { error: "UNAUTHORIZED" }),
    );

    renderWithQueryClient(<VoiceRangePage />);

    await user.click(screen.getByRole("button", { name: /추천 받기/ }));

    // alert 즉시 노출 — submit 에러 영역이 role="alert" 로 등장.
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(/저장에 실패했습니다/);
    expect(alert).toHaveTextContent("401");

    // retry 0건 — 첫 호출 1건만.
    expect(createVoiceRangeMock).toHaveBeenCalledTimes(1);

    // 시간 경과 (retry-after 잠재 구간) — 추가 호출 0건.
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(createVoiceRangeMock).toHaveBeenCalledTimes(1);

    // 부수효과 0건 — router.push / setVoiceRangeId 모두 호출 안 됨.
    expect(pushMock).not.toHaveBeenCalled();
    expect(sessionMock.state().setVoiceRangeId).not.toHaveBeenCalled();
  });

  // 시나리오 3 — mid-flight unmount (현재 동작 박제 + React state warning 0건)
  //
  // mutationFn pending 도중 unmount → 응답 도착 시 React state update warning 0건.
  // PR #985 가드 reaffirm.
  //
  // **현재 동작 박제**: useMutation 은 useQuery 와 달리 unmount 후 응답이 도착하면
  // onSuccess 콜백이 호출된다 (`useBaseMutation` 의 observer 는 mount 시점이 아닌
  // mutate 호출 시점에 lifecycle 이 시작되며, observer cleanup 이 onSuccess 를
  // 차단하지 않는다). 즉 unmount 후에도 setVoiceRangeId / pushMock 외부 부수효과는
  // 호출된다.
  //
  // 가드 의미: 본 테스트가 깨지면 다음 중 하나가 변한 것:
  //   (a) page.tsx 가 mutation 에 AbortController + signal 을 연결해 unmount 시
  //       응답을 무효화하도록 변경됨.
  //   (b) onSuccess 가 mountedRef / signal 가드를 추가해 unmount 후 외부 mutate 차단.
  //
  // 박제 사실:
  //   - React state warning 0건 (PR #985 reaffirm).
  //   - setVoiceRangeId 가 호출됨 (현재 race 동작 — 가드 없음 박제).
  //   - pushMock 가 호출됨 (현재 race 동작 — onSuccess 가 라우팅 트리거).
  it("시나리오 3: pending 중 unmount → 응답 도착 시 React state warning 0건 + onSuccess 부수효과 호출 (현재 동작 박제)", async () => {
    const user = userEvent.setup();

    const deferred =
      createDeferred<ReturnType<typeof buildVoiceRangeResponse>>();
    createVoiceRangeMock.mockReturnValueOnce(deferred.promise);

    const consoleErrorSpy = vi
      .spyOn(console, "error")
      .mockImplementation(() => {});

    const { unmount } = renderWithQueryClient(<VoiceRangePage />);

    await user.click(screen.getByRole("button", { name: /추천 받기/ }));

    await waitFor(() => {
      expect(createVoiceRangeMock).toHaveBeenCalledTimes(1);
    });

    // mutation pending 중 컴포넌트 unmount.
    unmount();

    // 응답 도착 — useMutation onSuccess 는 unmount 후에도 호출됨 (현재 동작).
    await act(async () => {
      deferred.resolve(buildVoiceRangeResponse({ id: 300 }));
      await deferred.promise;
    });

    await new Promise((resolve) => setTimeout(resolve, 50));

    // React state warning 0건 (PR #985 가드 reaffirm) — 외부 mutate 는 일어나지만
    // unmounted React component 의 setState 는 차단되어야 한다.
    const stateUpdateWarnings = consoleErrorSpy.mock.calls.filter((call) => {
      const first = call[0];
      return (
        typeof first === "string" &&
        first.includes("unmounted") &&
        first.includes("state update")
      );
    });
    expect(stateUpdateWarnings).toEqual([]);

    // 현재 동작 박제 — onSuccess 가 unmount 후에도 외부 부수효과를 호출한다.
    // 본 단언이 깨지면 (호출 0건으로 변경) page.tsx 에 mountedRef / signal 가드가
    // 추가됐다는 의미 — 의도적 개선이라면 본 단언을 명시적으로 갱신해야 한다.
    expect(sessionMock.state().setVoiceRangeId).toHaveBeenCalledWith(300);
    expect(pushMock).toHaveBeenCalledWith("/recommend");

    consoleErrorSpy.mockRestore();
  });

  // 시나리오 4 — 측정 도중 navigation cancel (현재 동작 박제 — cache leak)
  //
  // 사용자가 mutation pending 도중 다른 페이지로 이탈 (unmount). 응답이 도착하면
  // useMutation onSuccess 가 unmount 와 무관하게 호출돼 queryClient.setQueryData(
  // ["voice-range", sessionId]) 로 cache prime 된다.
  //
  // **현재 동작 박제**: 다음 페이지에서 같은 sessionId 로 useQuery(["voice-range",
  // sessionId]) 가 cache 히트로 응답을 소비할 수 있다. 사용자가 다른 sessionId 로
  // 진입하면 cache 키가 달라 leak 가 안 일어나지만, 같은 sessionId 로 재진입 시점에
  // **만료된 mutation 응답** 이 prime 되어 있을 가능성이 본 가드의 회귀 시나리오.
  //
  // 가드 의미: 본 테스트가 깨지면 (cache 가 undefined 가 되면):
  //   (a) onSuccess 가 mountedRef / signal 로 cache prime 을 차단하도록 변경됨.
  //   (b) AbortController 가 mutation 을 unmount 시 취소하도록 변경됨.
  //
  // 박제 사실:
  //   - client.getQueryData(["voice-range", TEST_SESSION_ID]) 가 응답으로 prime 됨
  //     (현재 race 동작 — 가드 없음 박제). 향후 개선 시 본 단언을 명시적으로 갱신.
  it("시나리오 4: 측정 도중 unmount → 응답 도착 시 setQueryData cache prime 됨 (현재 동작 박제)", async () => {
    const user = userEvent.setup();

    const deferred =
      createDeferred<ReturnType<typeof buildVoiceRangeResponse>>();
    createVoiceRangeMock.mockReturnValueOnce(deferred.promise);

    const { client, unmount } = renderWithExposedQueryClient(<VoiceRangePage />);

    await user.click(screen.getByRole("button", { name: /추천 받기/ }));

    await waitFor(() => {
      expect(createVoiceRangeMock).toHaveBeenCalledTimes(1);
    });

    // mutation pending 중 페이지 이탈 (예: 사용자가 다른 페이지로 navigation cancel).
    unmount();

    // 응답이 뒤늦게 도착 — useMutation onSuccess 는 unmount 후에도 호출되어 cache
    // prime 이 일어난다 (현재 동작).
    await act(async () => {
      deferred.resolve(buildVoiceRangeResponse({ id: 400 }));
      await deferred.promise;
    });

    await new Promise((resolve) => setTimeout(resolve, 50));

    // 현재 동작 박제 — cache 가 응답으로 prime 됨. 본 단언이 깨지면 onSuccess 에
    // 가드가 추가됐다는 의미 — 의도적 개선이라면 본 단언을 명시적으로 갱신해야 한다.
    const cached = client.getQueryData(["voice-range", TEST_SESSION_ID]);
    expect(cached).toMatchObject({ id: 400, sessionId: TEST_SESSION_ID });
  });

  // 시나리오 5 — 빈 sessionId 가드 (defensive 동작 박제)
  //
  // sessionMock 의 ensureSessionId 가 빈 문자열 반환하는 사고를 모사 — mutation 이
  // 호출되어 sessionId 가 빈 string 으로 전달되는지 단언. page.tsx 는 현재 ensureSessionId
  // 의 반환값을 가드 없이 mutation.mutate 에 전달한다 (가드는 ensureSessionId 의 책임).
  //
  // 가드 의미: 누군가 page.tsx 안에 `if (!sessionId) return;` 같은 추가 가드를 넣으면
  // 본 단언이 깨지면서 사용자에게 가드가 어떤 의미인지 명시되도록 강제한다 (현재 동작 박제).
  // 또한 ensureSessionId 가 책임을 갖고 항상 non-empty 를 반환해야 한다는 invariant 를
  // page.race 레이어에서 표시한다.
  it("시나리오 5: ensureSessionId 가 '' 반환 시 mutation 은 호출되지만 sessionId='' 가 그대로 전달된다 (defensive 박제)", async () => {
    const user = userEvent.setup();

    // ensureSessionId mock 을 빈 문자열 반환으로 override.
    const ensureSessionIdMock = vi.fn(() => "");
    sessionMock.set({ ensureSessionId: ensureSessionIdMock });

    const deferred =
      createDeferred<ReturnType<typeof buildVoiceRangeResponse>>();
    createVoiceRangeMock.mockReturnValueOnce(deferred.promise);

    renderWithQueryClient(<VoiceRangePage />);

    await user.click(screen.getByRole("button", { name: /추천 받기/ }));

    await waitFor(() => {
      expect(createVoiceRangeMock).toHaveBeenCalledTimes(1);
    });

    // ensureSessionId 호출 발생 검증.
    expect(ensureSessionIdMock).toHaveBeenCalledTimes(1);

    // mutation 의 첫 인자 sessionId 가 빈 문자열 그대로 전달됨 — page.tsx 는 추가
    // 가드를 안 한다 (ensureSessionId 가 책임). 본 단언이 깨지면 page.tsx 변경이
    // 명시적으로 드러난다.
    const firstCallArgs = createVoiceRangeMock.mock.calls[0][0];
    expect(firstCallArgs).toMatchObject({
      sessionId: "",
      lowestNoteMidi: 48,
      highestNoteMidi: 69,
      sourceMethod: "OCTAVE_PICK",
    });

    // cleanup — 응답 도착시켜 onSuccess router.push 가 act 안에서 호출되도록.
    await act(async () => {
      deferred.resolve(buildVoiceRangeResponse({ id: 500, sessionId: "" }));
      await deferred.promise;
    });
  });
});

/**
 * 본 파일에서 사용된 race-helpers (PR #1057/#1061) + fixture (#1115) reuse 카탈로그:
 *   - race-helpers:
 *     * makeQueryClient — renderWithQueryClient + renderWithExposedQueryClient 2곳
 *     * createDeferred — 시나리오 1 (2회) + 시나리오 3 (1회) + 시나리오 4 (1회) +
 *       시나리오 5 (1회) = 5회
 *   - fixture (`voice-range.ts`, 본 PR):
 *     * buildVoiceRangeResponse — 시나리오 1 (2회) + 시나리오 3 (1회) + 시나리오 4
 *       (1회) + 시나리오 5 (1회) = 5회 (inline `VoiceRangeResponse` 정의 0건)
 *     * TEST_SESSION_ID — 시나리오 4 cache 키 단언 1회
 *
 * PR #1115 의 LOC 절감 효과:
 *   - inline `VoiceRangeResponse` (~9 LOC) 패턴이 fixture 모듈로 옮겨가 본 파일 안
 *     5 시나리오 응답 셋업이 한 줄 builder 로 압축.
 *   - 추가 부수 효과: `page.test.tsx` 의 inline `VoiceRangeResponse` 정의 (#985 race
 *     가드 3건 + 기존 happy path 4건 총 7곳) 도 향후 같은 fixture 로 통합 가능 (별도
 *     사이클 — 본 PR 범위 외).
 */
