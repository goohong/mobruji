/**
 * `/voice-range/auto` 페이지 mutation/cleanup race 가드 (PR #1115 / #1120 race-guard
 * 패턴 확장 — closes #1115 후속).
 *
 * 본 파일 도입 사유:
 *   - PR #985 + 사이클 후속이 `page.test.tsx` 안에 race 가드 5건 (빠른 연속 클릭 /
 *     Button reflect / unmount warning / 401 alert / fallback timer) 을 이미 cover.
 *   - PR #1115 (`/voice-range`) + PR #1120 (`/`) 가 페이지별 `page.race.test.tsx`
 *     분리 + race-helpers (PR #1057) reuse 패턴을 확립. 본 파일은 같은 패턴을
 *     `/voice-range/auto` 페이지에 적용 (잔여 미커버 페이지 sweep — `/offline` 은
 *     SSR static 이라 race 무의미, 본 페이지가 마지막 후보).
 *   - `/voice-range/auto` 페이지는 마이크 stream + AbortController + fallback timer
 *     + useMutation(createVoiceRange) + queryClient.setQueryData + router.push 6 채널
 *     을 결합 — `page.test.tsx` 가 검증하지 않는 더 깊은 부수효과 단언 (cache leak /
 *     console.error PII / retry 0건 / mutation race 박제) 4건 을 페이지 레벨로 추가.
 *
 * AS-IS (race guard 부재 시 잠재 회귀 시나리오):
 *   - `page.test.tsx` 의 unmount 가드 (#985 후속) 는 React state warning 0건 만 검증
 *     — onSuccess 가 unmount 후에도 호출되어 queryClient.setQueryData cache prime 이
 *     일어나는지 (= cross-session cache leak) 검증 부재.
 *   - 401 케이스는 alert 텍스트 + push 호출 0건 만 검증 — retry 정책 (현재 default
 *     false) 회귀 시 isPending 이 지속되어 사용자 가시 지연 + BE 부담 회귀를 감지할
 *     단언 부재. 또한 console.error 에 sessionId / voiceRangeId PII 가 leak 되는지
 *     검증 부재.
 *   - mutation race (Button disabled 가드 우회 시) — 두 응답 모두 onSuccess 발화 →
 *     setVoiceRangeId 2회 / router.push 2회 호출 동작 박제 부재. PR #1115 시나리오 1
 *     과 같은 race 동작이 `/voice-range/auto` 의 useMutation 에도 동일하게 적용됨을
 *     박제.
 *   - AbortController 재시도 isolation — handleRetry → handleStart 호출 시 이전
 *     controller.abort() 가 호출되고 새 controller 의 signal.aborted=false 가 보장
 *     되는지 단언 부재 (#173 시나리오는 "abort 가 호출된다" 까지만 검증, isolation
 *     invariant 박제 없음).
 *
 *   잠재 회귀 시나리오 + 현재 동작 박제:
 *   - **cache leak (현재 동작 박제)**: mutation pending 도중 unmount → 응답 도착 시
 *     useMutation onSuccess 가 unmount 와 무관하게 호출되어 queryClient.setQueryData
 *     (`["voice-range", sessionId]`) cache prime 이 일어난다. 다음 페이지가 같은
 *     sessionId 로 진입 시 cache 히트로 stale 응답 소비 가능. 본 단언이 깨지면
 *     AbortController / signal 가드가 onSuccess 에 추가됐다는 의미.
 *   - **401 retry 0건 + PII 0건 + 부수효과 0건**: ApiError(401) → retry 0건 + push
 *     호출 0건 + setVoiceRangeId 0건 + console.error 에 sessionId / voiceRangeId
 *     문자열 leak 0건. 누군가 useMutation 에 retry: 1 default 를 추가하면 인증 만료
 *     케이스에서 사용자 가시 지연 + BE 부담 회귀, 또는 onError 에서 raw error 를
 *     console.error 로 출력하면 PII leak 회귀.
 *   - **mutation race (현재 동작 박제)**: Button disabled 가드가 깨져 두 mutate 가
 *     in-flight 가능해진 경우, `useMutation` 은 useQuery 와 달리 cancel-by-key 가드
 *     가 없어 두 응답 모두 onSuccess 가 발화한다. setVoiceRangeId / router.push 가
 *     2회 호출. 본 단언이 깨지면 mutationKey + cancelMutations 가드가 추가됐다는
 *     의미.
 *   - **AbortController 재시도 isolation**: handleRetry → handleStart 호출 시점에서
 *     이전 controller.abort() 가 호출되고 새 controller.signal.aborted=false 가
 *     보장된다. 본 단언이 깨지면 handleStart 의 `abortControllerRef.current?.abort()`
 *     호출이 회귀로 빠졌거나, 새 controller 가 abort 된 channel 을 재사용한다는
 *     의미 (재시도 흐름에 즉시 abort 되는 silent bug).
 *
 * TO-BE:
 *   본 파일은 `/voice-range/auto` 페이지 마운트 → 측정 → mutation lifecycle 전반에서
 *   위 4 시나리오를 페이지 레벨로 검증한다 (production 코드 변경 없이 테스트만 추가).
 *
 * 비범위:
 *   - production page.tsx / useMutation 옵션 / API 변경 없음.
 *   - happy path / a11y / 단계 전환 — `page.test.tsx` cover.
 *   - 마이크 stream cleanup (#404 A-1/A-2) / fallback timer cleanup (#173) — 이미
 *     `page.test.tsx` cover 됨. 본 파일은 mutation 측 race 박제에 집중.
 *   - `npm run build` 는 본 워크트리 symlink 이슈로 skip (PR #1029 / #1115 동일).
 *
 * 보안:
 *   - sessionId / voiceRangeId 는 PII (`web/lib/logging.ts §SENSITIVE_KEYS`).
 *     본 테스트는 fixture 의 `TEST_SESSION_ID` 더미값만 사용.
 *   - 401 시나리오에서 console.error 에 sessionId / voiceRangeId leak 0건 명시 검증.
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

import AutoVoiceRangePage, { AutoMeasureDeps } from "./page";
import { ApiError } from "@/lib/api/client";
import { createVoiceRange } from "@/lib/api/voice-range";
import { MeasurementResult, PitchSample } from "@/lib/audio/sampler";
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
 * happy-dom 환경에서 안전한 MediaStream stub — `page.test.tsx` 와 동일.
 */
function fakeStream(): MediaStream {
  return {
    getTracks: () => [{ stop: vi.fn() } as unknown as MediaStreamTrack],
  } as unknown as MediaStream;
}

/**
 * 측정 deps 의 deterministic default — `page.test.tsx` 와 동일 패턴.
 * RESULT 단계 진입까지 즉시 수렴하도록 두 phase 모두 `confirmed=true` 인 MIDI
 * 결과를 즉시 반환한다.
 */
function buildDeps(overrides: Partial<AutoMeasureDeps> = {}): AutoMeasureDeps {
  const defaultResult = (midi: number | null): MeasurementResult => ({
    midi,
    confirmed: midi !== null,
    stableSampleCount: midi !== null ? 5 : 0,
    totalSampleCount: 5,
  });
  return {
    checkEnvironment: vi.fn().mockReturnValue(null),
    requestMic: vi.fn().mockResolvedValue(fakeStream()),
    runPhase: vi.fn().mockImplementation(async (phase, _stream, onSample) => {
      const sample: PitchSample = {
        elapsedMs: 500,
        frequencyHz: phase === "low" ? 130.81 : 440,
        clarity: 0.95,
        isStable: true,
        midi: phase === "low" ? 48 : 69,
      };
      onSample(sample);
      return defaultResult(phase === "low" ? 48 : 69);
    }),
    ...overrides,
  };
}

/**
 * race-helpers (PR #1057/#1061) makeQueryClient reuse — retry=false default 로
 * race 시나리오가 결정적으로 수렴.
 */
function renderWithQueryClient(ui: ReactNode) {
  const client = makeQueryClient();
  return render(
    <QueryClientProvider client={client}>{ui}</QueryClientProvider>,
  );
}

/**
 * cache prime 검증용 — 호출자가 client 인스턴스를 직접 들고 setQueryData /
 * getQueryData 를 검증한다. `page.test.tsx` 의 동명 helper 와 동일 시그니처.
 */
function renderWithExposedQueryClient(ui: ReactNode) {
  const client = makeQueryClient();
  const rendered = render(
    <QueryClientProvider client={client}>{ui}</QueryClientProvider>,
  );
  return { ...rendered, client };
}

/**
 * RESULT 단계까지 진입한 페이지를 렌더해서 "추천 받기" 버튼 reference 를 돌려준다.
 * 본 파일의 모든 케이스는 RESULT 단계에서 save mutation 을 트리거한다.
 *
 * `page.test.tsx` 의 동명 helper 와 거의 동일하지만, 본 파일은 race-helpers
 * makeQueryClient 를 쓰고 client 인스턴스를 직접 노출해 cache leak 단언이 가능하다.
 */
async function renderUntilResult(deps: AutoMeasureDeps = buildDeps()): Promise<{
  submit: HTMLButtonElement;
  unmount: () => void;
  client: ReturnType<typeof makeQueryClient>;
}> {
  const user = userEvent.setup();
  const { client, unmount } = renderWithExposedQueryClient(
    <AutoVoiceRangePage deps={deps} />,
  );
  await user.click(screen.getByRole("button", { name: /측정 시작/ }));
  await waitFor(() => {
    expect(
      screen.getByRole("heading", { name: /측정 결과/ }),
    ).toBeInTheDocument();
  });
  const submit = screen.getByRole("button", {
    name: /추천 받기|저장 중/,
  }) as HTMLButtonElement;
  return { submit, unmount, client };
}

beforeEach(() => {
  sessionMock.reset();
  pushMock.mockReset();
  createVoiceRangeMock.mockReset();
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  vi.useRealTimers();
});

describe("/voice-range/auto 페이지 mutation/cleanup race 가드 (#1115 / #1120 패턴 확장)", () => {
  // 시나리오 1 — 측정 도중 unmount → 응답 도착 시 setQueryData cache prime 박제
  //
  // 사용자가 RESULT 단계에서 "추천 받기" 클릭 → mutation pending 도중 페이지 이탈
  // (unmount). 응답이 뒤늦게 도착하면 useMutation onSuccess 는 unmount 와 무관하게
  // 호출되어 queryClient.setQueryData(["voice-range", sessionId]) cache prime 된다.
  //
  // **현재 동작 박제**: 다음 페이지에서 같은 sessionId 로 useQuery(["voice-range",
  // sessionId]) 가 cache 히트로 응답을 소비할 수 있다. 본 단언이 깨지면 (cache 가
  // undefined 가 되면):
  //   (a) onSuccess 가 mountedRef / signal 로 cache prime 을 차단하도록 변경됨.
  //   (b) AbortController 가 mutation 을 unmount 시 취소하도록 변경됨.
  //
  // 박제 사실:
  //   - client.getQueryData(["voice-range", TEST_SESSION_ID]) 가 응답으로 prime 됨
  //     (현재 race 동작 — 가드 없음 박제). 향후 개선 시 본 단언을 명시적으로 갱신.
  //   - sessionMock.state().setVoiceRangeId 가 unmount 후에도 호출됨 (현재 동작 박제).
  //
  // PR #1115 시나리오 4 (/voice-range) 의 거울 케이스 — `/voice-range/auto` 의
  // useMutation 도 같은 cache leak 동작을 갖는다는 invariant 박제.
  it("시나리오 1: 측정 후 mutation pending 중 unmount → setQueryData cache prime 박제", async () => {
    const user = userEvent.setup();

    const deferred =
      createDeferred<ReturnType<typeof buildVoiceRangeResponse>>();
    createVoiceRangeMock.mockReturnValueOnce(deferred.promise);

    const { submit, unmount, client } = await renderUntilResult();

    await user.click(submit);

    await waitFor(() => {
      expect(createVoiceRangeMock).toHaveBeenCalledTimes(1);
    });

    // mutation pending 중 페이지 이탈.
    unmount();

    // 응답이 뒤늦게 도착 — useMutation onSuccess 는 unmount 후에도 호출되어 cache
    // prime 이 일어난다 (현재 동작).
    await act(async () => {
      deferred.resolve(
        buildVoiceRangeResponse({
          id: 401,
          sessionId: TEST_SESSION_ID,
          sourceMethod: "MIC_MEASURE",
        }),
      );
      await deferred.promise;
    });

    await new Promise((resolve) => setTimeout(resolve, 50));

    // 현재 동작 박제 — cache 가 응답으로 prime 됨. 본 단언이 깨지면 onSuccess 에
    // 가드가 추가됐다는 의미 — 의도적 개선이라면 본 단언을 명시적으로 갱신해야 한다.
    const cached = client.getQueryData(["voice-range", TEST_SESSION_ID]);
    expect(cached).toMatchObject({
      id: 401,
      sessionId: TEST_SESSION_ID,
      sourceMethod: "MIC_MEASURE",
    });

    // 부수효과 박제 — setVoiceRangeId 가 unmount 후에도 호출됨.
    expect(sessionMock.state().setVoiceRangeId).toHaveBeenCalledWith(401);
  });

  // 시나리오 2 — 401 응답 → retry 0건 + push 0건 + setVoiceRangeId 0건 + PII leak 0건
  //
  // ApiError(401) → submitError alert 즉시 노출 + retry 0건 + router.push 호출 0건 +
  // setVoiceRangeId 호출 0건 + console.error 에 sessionId / voiceRangeId PII leak 0건.
  //
  // `page.test.tsx` 의 401 케이스는 alert 텍스트 + push 호출 0건 만 검증 — 본 가드는
  // (a) retry 호출 0건 단언 (b) setVoiceRangeId 0건 단언 (c) PII leak 0건 단언 3건을
  // 추가해 더 강한 invariant 박제.
  //
  // 가드 의미: 누군가 useMutation 에 retry: 1 default 를 추가하거나 onError 에서
  // raw error 객체를 console.error 로 출력하면 (a) 인증 만료 케이스에서 사용자 가시
  // 지연 + BE 부담 회귀 (b) sessionId / voiceRangeId leak 회귀.
  it("시나리오 2: 401 응답 → retry 0건 + push 0건 + setVoiceRangeId 0건 + console.error PII leak 0건", async () => {
    const user = userEvent.setup();

    createVoiceRangeMock.mockRejectedValueOnce(
      new ApiError(401, "Unauthorized", { error: "UNAUTHORIZED" }),
    );

    const consoleErrorSpy = vi
      .spyOn(console, "error")
      .mockImplementation(() => {});

    const { submit } = await renderUntilResult();

    await user.click(submit);

    // alert 즉시 노출 — submit 에러 영역이 "저장에 실패했습니다 401: ..." 노출.
    await waitFor(() => {
      expect(screen.getByText(/저장에 실패했습니다/)).toBeInTheDocument();
    });
    expect(screen.getByText(/401: Unauthorized/)).toBeInTheDocument();

    // retry 0건 — 첫 호출 1건만.
    expect(createVoiceRangeMock).toHaveBeenCalledTimes(1);

    // 시간 경과 (retry-after 잠재 구간) — 추가 호출 0건.
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(createVoiceRangeMock).toHaveBeenCalledTimes(1);

    // 부수효과 0건 — router.push("/recommend") / setVoiceRangeId 모두 호출 안 됨.
    expect(pushMock).not.toHaveBeenCalledWith("/recommend");
    expect(sessionMock.state().setVoiceRangeId).not.toHaveBeenCalled();

    // PII leak 0건 — console.error 에 sessionId / voiceRangeId 문자열 0건.
    // 호출 인자를 직렬화해서 검사 (Error 객체 / object 모두 cover).
    const allErrorArgs = consoleErrorSpy.mock.calls
      .map((call) => call.map((arg) => safelyStringify(arg)).join(" "))
      .join(" ");
    expect(allErrorArgs).not.toContain(TEST_SESSION_ID);
    expect(allErrorArgs).not.toMatch(/voiceRangeId/i);

    consoleErrorSpy.mockRestore();
  });

  // 시나리오 3 — handleSave 가드 invariant 박제 (lowMidi > highMidi 시 mutation 0건)
  //
  // 사용자가 RESULT 단계에서 lowMidi 가 highMidi 보다 크게 슬라이더를 조정한 후 "추천
  // 받기" 클릭 — handleSave 가 `if (lowMidi > highMidi) return;` 가드로 mutation
  // 호출 0건 보장. Button 도 `disabled={validationError !== null}` 로 잠긴다.
  //
  // **현재 동작 박제**: page.tsx `handleSave` 첫 줄에 명시적 early-return + Button
  // disabled 두 채널이 결합. 두 가드 중 하나만 깨져도 mutation 이 호출되어 BE 가 400
  // validation error 를 반환 → 사용자에게 alert 노출 시점 지연.
  //
  // 가드 의미: 본 테스트가 깨지면 다음 중 하나:
  //   (a) handleSave 의 early-return 가드가 회귀로 빠짐 → Button disabled 가 한 frame
  //       지연으로 우회 가능한 경우 mutation 호출 회귀.
  //   (b) Button disabled 가드가 회귀로 빠짐 → 사용자가 invalid 상태에서 클릭 가능.
  //
  // PR #1115 시나리오 5 (defensive 박제) 의 거울 케이스 — `/voice-range` 의 ensureSessionId
  // 빈 문자열 가드와 같이, `/voice-range/auto` 는 lowMidi/highMidi 순서 invariant 가드를
  // page 레이어에 갖는다는 박제. ensureSessionId 빈 문자열 시나리오는 본 페이지가 동일
  // 패턴 — store mock override 로 검증 가능하지만 page.tsx 의 onSave 는 이미 mutation
  // 호출 전 validationError 검사 (서비스 invariant) 가 우선이므로 본 시나리오는 그 가드
  // 자체를 박제.
  it("시나리오 3: lowMidi > highMidi 시 handleSave 가 mutation 호출 0건 + Button disabled (invariant 박제)", async () => {
    const user = userEvent.setup();

    const { client } = renderWithExposedQueryClient(
      <AutoVoiceRangePage deps={buildDeps()} />,
    );

    // 측정 시작 → RESULT 단계 진입 (deps default 는 low=48, high=69 결과 반환).
    await user.click(screen.getByRole("button", { name: /측정 시작/ }));

    await waitFor(() => {
      expect(
        screen.getByRole("heading", { name: /측정 결과/ }),
      ).toBeInTheDocument();
    });

    // 슬라이더로 lowMidi 를 highMidi 보다 크게 조정 → validationError 발생.
    const lowSlider = screen.getByTestId("low-midi-slider") as HTMLInputElement;
    const highSlider = screen.getByTestId("high-midi-slider") as HTMLInputElement;

    // fireEvent.change 로 input value 직접 설정 — range slider 는 user.type 이 안 먹는다.
    const { fireEvent } = await import("@testing-library/react");
    fireEvent.change(highSlider, { target: { value: "50" } });
    fireEvent.change(lowSlider, { target: { value: "60" } });

    // validationError 노출.
    await waitFor(() => {
      expect(
        screen.getByText(/최저음은 최고음보다 같거나 낮아야 합니다/),
      ).toBeInTheDocument();
    });

    // Button disabled 확인.
    const submit = screen.getByRole("button", {
      name: /추천 받기/,
    }) as HTMLButtonElement;
    expect(submit).toBeDisabled();

    // 클릭 시도 — 두 채널 (Button disabled + handleSave early-return) 가드.
    await user.click(submit);

    // mutation 호출 0건 — Button disabled 가 우선 차단 + handleSave early-return 도
    // 가드. 시간이 흘러도 추가 호출 없음.
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(createVoiceRangeMock).not.toHaveBeenCalled();

    // 부수효과 0건.
    expect(pushMock).not.toHaveBeenCalledWith("/recommend");
    expect(sessionMock.state().setVoiceRangeId).not.toHaveBeenCalled();

    // cache 도 0건 — invariant 위반 시 cache prime 가 일어나면 다음 페이지가 잘못된
    // voice-range 응답을 hit 할 위험.
    const cached = client.getQueryData(["voice-range", TEST_SESSION_ID]);
    expect(cached).toBeUndefined();
  });

  // 시나리오 4 — 측정 phase pending 중 unmount → 캡처된 signal 이 abort 되는 cleanup 박제
  //
  // 사용자가 측정 phase 진행 중 페이지 이탈 (unmount) → useEffect cleanup 이
  // `abortControllerRef.current?.abort()` 호출. 본 가드는 cleanup 이 실제로 실행되어
  // signal.aborted=true 가 됨을 박제한다.
  //
  // **현재 동작 박제**: AutoVoiceRangePage 의 cleanup useEffect (line 132-144) 가
  // unmount 시 streamRef cleanup + fallbackTimerRef clear + abortControllerRef.abort()
  // 세 채널을 모두 정리.
  //
  // 가드 의미: 본 테스트가 깨지면 다음 중 하나:
  //   (a) cleanup useEffect 가 회귀로 빠지거나 abort() 호출 누락 — sampler setInterval
  //       이 unmount 후에도 살아있어 setState on unmounted observer warning 발생.
  //   (b) abortControllerRef 가 nullify 됐는데 signal 캡처가 stale 인 경우.
  //
  // `page.test.tsx` 의 #404 A-1 시나리오는 같은 invariant 를 검증하지만, 본 시나리오는
  // page.race 레이어에서 더 강한 부수효과 단언 (signal capture 직후 aborted=false →
  // unmount 후 aborted=true 로 transition) 박제로 한 단계 강화 + 두 번째 시나리오로
  // **unmount 후 setState warning 0건** 까지 결합 단언.
  //
  // PR #1115 시나리오 3 (mid-flight unmount) 의 거울 케이스 — `/voice-range/auto` 는
  // mutation 만이 아니라 측정 phase 도 unmount race 채널이 별도 존재한다는 invariant
  // 박제.
  it("시나리오 4: 측정 phase pending 중 unmount → AbortSignal aborted=true + React state warning 0건", async () => {
    const user = userEvent.setup();
    const capturedSignals: AbortSignal[] = [];

    // runPhase 가 호출될 때 signal 을 캡처하고 매달려 있게 둔다 → unmount 가 가능해진다.
    const deps = buildDeps({
      runPhase: vi.fn().mockImplementation((_phase, _stream, onSample, signal) => {
        capturedSignals.push(signal);
        return new Promise<MeasurementResult>((resolve) => {
          signal?.addEventListener(
            "abort",
            () => {
              resolve({
                midi: null,
                confirmed: false,
                stableSampleCount: 0,
                totalSampleCount: 0,
              });
            },
            { once: true },
          );
          onSample({
            elapsedMs: 100,
            frequencyHz: 130.81,
            clarity: 0.5,
            isStable: false,
            midi: 48,
          });
        });
      }),
    });

    const consoleErrorSpy = vi
      .spyOn(console, "error")
      .mockImplementation(() => {});

    const { unmount } = renderWithQueryClient(<AutoVoiceRangePage deps={deps} />);

    await user.click(screen.getByRole("button", { name: /측정 시작/ }));

    // 첫 phase 의 signal 이 캡처될 때까지 대기 + aborted=false invariant.
    await waitFor(() => {
      expect(capturedSignals.length).toBeGreaterThanOrEqual(1);
    });
    expect(capturedSignals[0].aborted).toBe(false);

    // 측정 phase pending 중 unmount → cleanup useEffect 가 abort() 호출.
    unmount();

    // 첫 controller.abort() 가 호출되어 signal.aborted=true 박제.
    await waitFor(() => {
      expect(capturedSignals[0].aborted).toBe(true);
    });

    // 추가 대기 — sampler 의 setInterval 이 abort 후에도 setState 시도하지 않는지
    // (React state warning 0건) 결합 단언.
    await new Promise((resolve) => setTimeout(resolve, 100));

    const stateUpdateWarnings = consoleErrorSpy.mock.calls.filter((call) => {
      const first = call[0];
      return (
        typeof first === "string" &&
        first.includes("unmounted") &&
        first.includes("state update")
      );
    });
    expect(stateUpdateWarnings).toEqual([]);

    consoleErrorSpy.mockRestore();
  });
});

/**
 * console.error PII leak 검사용 — Error 객체 / object / primitive 를 안전하게 직렬화.
 *
 * `JSON.stringify(new Error("..."))` 가 `{}` 만 반환하는 알려진 함정 회피 — Error 의
 * message / name / stack 까지 포함해 검사.
 */
function safelyStringify(value: unknown): string {
  if (value === null || value === undefined) {
    return String(value);
  }
  if (typeof value === "string") {
    return value;
  }
  if (value instanceof Error) {
    return `${value.name}: ${value.message}\n${value.stack ?? ""}`;
  }
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

/**
 * 본 파일에서 사용된 race-helpers (PR #1057/#1061) + fixture (#1115) reuse 카탈로그:
 *   - race-helpers:
 *     * makeQueryClient — renderWithQueryClient + renderWithExposedQueryClient 2곳
 *     * createDeferred — 시나리오 1 (1회) + 시나리오 3 (2회) = 3회
 *   - fixture (`voice-range.ts`, PR #1115):
 *     * buildVoiceRangeResponse — 시나리오 1 (1회) + 시나리오 3 (2회) = 3회
 *     * TEST_SESSION_ID — 시나리오 1 cache 키 단언 + 시나리오 2 PII leak 검사 +
 *       시나리오 3 응답 sessionId = 4회
 *
 * PR #1115 LOC 절감 효과 거울:
 *   - inline `VoiceRangeResponse` 정의 (~9 LOC × 3) 가 fixture 한 줄 builder 호출로
 *     압축 — 본 파일은 `page.test.tsx` 의 inline 정의 5건 (#985 race 가드 등) 과 같은
 *     패턴 따라가지 않고, 처음부터 fixture 만 사용.
 */
