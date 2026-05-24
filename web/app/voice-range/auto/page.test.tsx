/**
 * 음역대 자동 측정 페이지 테스트.
 *
 * voice-range-auto-measurement.md §6 PR C / §7.
 *  - 권한 거부 시 fallback 라우팅 1건.
 *  - mock 측정 흐름 (low/high → 결과 화면) 1건.
 *  - 수동 보정 슬라이더 1건.
 *  - 저장 → createVoiceRange("MIC_MEASURE") 호출 + setVoiceRangeId + /recommend 라우팅 1건.
 *  - 초기 a11y 1건.
 *
 * 디자인 결정: AutoVoiceRangePage 는 `deps` prop으로 mediaDevices/AudioContext 의존을
 * 주입받는다. 테스트는 deps만 mock해서 deterministic하게 흐름을 검증한다 — Web Audio API를
 * happy-dom에서 흉내 내려는 비용을 회피.
 */

import { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import AutoVoiceRangePage, {
  AutoMeasureDeps,
} from "./page";
import { ApiError } from "@/lib/api/client";
import { createVoiceRange } from "@/lib/api/voice-range";
import { expectNoA11yViolations } from "@/lib/test-helpers/a11y";
import { MeasurementResult, PitchSample } from "@/lib/audio/sampler";

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

function renderWithQueryClient(ui: ReactNode) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

/**
 * cache prime 검증용 — 호출자가 client 인스턴스를 직접 들고 setQueryData 가
 * 일어났는지 확인할 수 있게 한다 (#282).
 */
function renderWithExposedQueryClient(ui: ReactNode) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  const rendered = render(
    <QueryClientProvider client={client}>{ui}</QueryClientProvider>,
  );
  return { ...rendered, client };
}

function fakeStream(): MediaStream {
  // happy-dom에는 MediaStream 생성자가 없을 수 있으므로 간이 stub.
  return {
    getTracks: () => [{ stop: vi.fn() } as unknown as MediaStreamTrack],
  } as unknown as MediaStream;
}

function buildDeps(overrides: Partial<AutoMeasureDeps> = {}): AutoMeasureDeps {
  const defaultResult = (midi: number | null): MeasurementResult => ({
    midi,
    confirmed: midi !== null,
    stableSampleCount: midi !== null ? 5 : 0,
    totalSampleCount: 5,
  });
  return {
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

describe("AutoVoiceRangePage 권한 / 안내", () => {
  it("초기 진입 시 마이크 권한 안내와 측정 시작 버튼이 보인다", () => {
    renderWithQueryClient(<AutoVoiceRangePage deps={buildDeps()} />);
    expect(
      screen.getByRole("heading", { name: /마이크로 음역대 측정하기/ }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/audio 데이터는 서버로 업로드하지 않습니다/),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /측정 시작/ })).toBeEnabled();
  });

  it("권한 거부 시 fallback 안내가 뜨고 /voice-range로 라우팅한다", async () => {
    const user = userEvent.setup();
    const notAllowed = Object.assign(new Error("denied"), {
      name: "NotAllowedError",
    });
    const deps = buildDeps({
      requestMic: vi.fn().mockRejectedValue(notAllowed),
    });
    renderWithQueryClient(<AutoVoiceRangePage deps={deps} />);

    await user.click(screen.getByRole("button", { name: /측정 시작/ }));

    await waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent(
        /마이크 권한이 거부되었습니다/,
      );
    });

    // fallback push는 setTimeout 1.2s — 기본 vitest testTimeout(5s) 내에 도달.
    await waitFor(
      () => {
        expect(pushMock).toHaveBeenCalledWith("/voice-range");
      },
      { timeout: 2500 },
    );
  });

  it("권한 거부 후 '지금 수동 입력으로 이동' 버튼 클릭 시 즉시 /voice-range로 push 한다", async () => {
    const user = userEvent.setup();
    const notAllowed = Object.assign(new Error("denied"), {
      name: "NotAllowedError",
    });
    const deps = buildDeps({
      requestMic: vi.fn().mockRejectedValue(notAllowed),
    });
    renderWithQueryClient(<AutoVoiceRangePage deps={deps} />);

    await user.click(screen.getByRole("button", { name: /측정 시작/ }));

    const manualBtn = await screen.findByRole("button", {
      name: /지금 수동 입력으로 이동/,
    });
    await user.click(manualBtn);

    // setTimeout(1.2s) 만료 전에 즉시 push 가 호출되었는지 확인.
    expect(pushMock).toHaveBeenCalledWith("/voice-range");
  });
});

describe("AutoVoiceRangePage 측정 중 UI", () => {
  it("측정 중 progress bar 와 마이크 레벨 meter 가 표시된다", async () => {
    const user = userEvent.setup();
    // low phase 를 일부러 미해결 promise 로 두어 측정 중 화면을 유지.
    let resolveLow: (result: MeasurementResult) => void = () => {};
    const deps = buildDeps({
      runPhase: vi.fn().mockImplementation((phase, _stream, onSample) => {
        if (phase === "low") {
          return new Promise<MeasurementResult>((resolve) => {
            // 2.5s 경과 시점의 샘플을 1회 흘려보낸다 — progress 50% / clarity 0.95.
            onSample({
              elapsedMs: 2500,
              frequencyHz: 130.81,
              clarity: 0.95,
              isStable: true,
              midi: 48,
            });
            resolveLow = resolve;
          });
        }
        return Promise.resolve<MeasurementResult>({
          midi: 69,
          confirmed: true,
          stableSampleCount: 5,
          totalSampleCount: 5,
        });
      }),
    });

    renderWithQueryClient(<AutoVoiceRangePage deps={deps} />);
    await user.click(screen.getByRole("button", { name: /측정 시작/ }));

    // 측정 중 화면 → progressbar 와 meter 가 함께 보인다.
    const progressBar = await screen.findByRole("progressbar", {
      name: /가장 낮은 음 측정 진행률/,
    });
    expect(progressBar).toHaveAttribute("aria-valuenow", "50");

    const levelMeter = screen.getByRole("meter", {
      name: /마이크 입력 레벨/,
    });
    expect(levelMeter).toHaveAttribute("aria-valuenow", "95");
    expect(screen.getByTestId("signal-status")).toHaveTextContent(/감지 중/);

    // 측정을 마무리해 테스트가 매달리지 않게 한다.
    resolveLow({
      midi: 48,
      confirmed: true,
      stableSampleCount: 5,
      totalSampleCount: 5,
    });
  });
});

describe("AutoVoiceRangePage 측정 흐름", () => {
  it("측정 두 phase 후 결과 화면에 측정 MIDI가 슬라이더 기본값으로 들어간다", async () => {
    const user = userEvent.setup();
    renderWithQueryClient(<AutoVoiceRangePage deps={buildDeps()} />);

    await user.click(screen.getByRole("button", { name: /측정 시작/ }));

    await waitFor(() => {
      expect(
        screen.getByRole("heading", { name: /측정 결과/ }),
      ).toBeInTheDocument();
    });

    // 측정 결과: low=48(도3/C3), high=69(라4/A4) 가 슬라이더 표시값에 반영.
    // 이슈 #318: 한국어 음명 병기 + MIDI 숫자.
    expect(screen.getByText(/도3 \(C3\) · MIDI 48/)).toBeInTheDocument();
    expect(screen.getByText(/라4 \(A4\) · MIDI 69/)).toBeInTheDocument();
  });

  it("결과 화면의 '다시 측정하기' 버튼을 누르면 PERMISSION 단계로 돌아간다", async () => {
    const user = userEvent.setup();
    renderWithQueryClient(<AutoVoiceRangePage deps={buildDeps()} />);
    await user.click(screen.getByRole("button", { name: /측정 시작/ }));

    await waitFor(() => {
      expect(
        screen.getByRole("heading", { name: /측정 결과/ }),
      ).toBeInTheDocument();
    });

    await user.click(screen.getByRole("button", { name: /다시 측정하기/ }));

    // PERMISSION 단계 표지인 "측정 시작" 버튼이 다시 나타난다.
    expect(
      screen.getByRole("button", { name: /측정 시작/ }),
    ).toBeInTheDocument();
    // 결과 헤딩은 사라진다.
    expect(
      screen.queryByRole("heading", { name: /측정 결과/ }),
    ).not.toBeInTheDocument();
  });

  // (#173 항목) RESULT 단계에서 '다시 측정하기' 누른 직후, 직전 측정의
  // AbortController 가 즉시 abort 되어야 한다. retry 시작 시 정리 누락이 있으면
  // 빠른 재측정 흐름에서 살아있던 sampler timer / fallback redirect timer 가
  // 새 시도와 race 해 PERMISSION 화면 밖으로 사용자를 튕길 수 있다.
  it("'다시 측정하기' 클릭 시 직전 시도의 AbortSignal 이 즉시 abort 된다 (#173)", async () => {
    const user = userEvent.setup();
    const capturedSignals: AbortSignal[] = [];
    const deps = buildDeps({
      runPhase: vi.fn().mockImplementation(async (phase, _stream, onSample, signal) => {
        if (signal) {
          capturedSignals.push(signal);
        }
        const sample: PitchSample = {
          elapsedMs: 500,
          frequencyHz: phase === "low" ? 130.81 : 440,
          clarity: 0.95,
          isStable: true,
          midi: phase === "low" ? 48 : 69,
        };
        onSample(sample);
        return {
          midi: phase === "low" ? 48 : 69,
          confirmed: true,
          stableSampleCount: 5,
          totalSampleCount: 5,
        } as MeasurementResult;
      }),
    });

    renderWithQueryClient(<AutoVoiceRangePage deps={deps} />);
    await user.click(screen.getByRole("button", { name: /측정 시작/ }));

    await waitFor(() => {
      expect(
        screen.getByRole("heading", { name: /측정 결과/ }),
      ).toBeInTheDocument();
    });

    // 첫 시도의 controller(low/high 두 phase 가 같은 controller 공유).
    expect(capturedSignals.length).toBeGreaterThan(0);
    const firstSignal = capturedSignals[0];
    expect(firstSignal.aborted).toBe(false);

    await user.click(screen.getByRole("button", { name: /다시 측정하기/ }));

    // retry 가 직전 controller 를 정리해 signal 이 abort 되어야 한다.
    expect(firstSignal.aborted).toBe(true);
  });

  it("수동 보정 슬라이더로 lowMidi 값을 조정할 수 있다", async () => {
    const user = userEvent.setup();
    renderWithQueryClient(<AutoVoiceRangePage deps={buildDeps()} />);
    await user.click(screen.getByRole("button", { name: /측정 시작/ }));

    await waitFor(() => {
      expect(
        screen.getByRole("heading", { name: /측정 결과/ }),
      ).toBeInTheDocument();
    });

    const lowSlider = screen.getByTestId("low-midi-slider") as HTMLInputElement;
    // userEvent의 range 처리가 환경마다 다르므로 fireEvent.change로 직접 값 설정.
    fireEvent.change(lowSlider, { target: { value: "50" } });

    // 이슈 #318: 한국어 (SPN) · MIDI 형식.
    expect(screen.getByText(/레3 \(D3\) · MIDI 50/)).toBeInTheDocument();
  });
});

describe("AutoVoiceRangePage 저장", () => {
  it("저장 클릭 → createVoiceRange(MIC_MEASURE) 호출 + setVoiceRangeId + /recommend 라우팅", async () => {
    const user = userEvent.setup();
    createVoiceRangeMock.mockResolvedValueOnce({
      id: 91,
      sessionId: "test-session-id",
      lowestNoteMidi: 48,
      highestNoteMidi: 69,
      sourceMethod: "MIC_MEASURE",
      createdAt: "2026-05-21T00:00:00Z",
      updatedAt: "2026-05-21T00:00:00Z",
    });

    renderWithQueryClient(<AutoVoiceRangePage deps={buildDeps()} />);
    await user.click(screen.getByRole("button", { name: /측정 시작/ }));

    await waitFor(() => {
      expect(
        screen.getByRole("heading", { name: /측정 결과/ }),
      ).toBeInTheDocument();
    });

    await user.click(screen.getByRole("button", { name: /추천 받기/ }));

    await waitFor(() => {
      expect(createVoiceRangeMock).toHaveBeenCalledTimes(1);
    });
    expect(createVoiceRangeMock).toHaveBeenCalledWith({
      sessionId: "test-session-id",
      lowestNoteMidi: 48,
      highestNoteMidi: 69,
      sourceMethod: "MIC_MEASURE",
    });

    await waitFor(() => {
      expect(sessionMock.state().setVoiceRangeId).toHaveBeenCalledWith(91);
    });
    expect(pushMock).toHaveBeenCalledWith("/recommend");
  });

  // (closes #282) /recommend 진입 시 voice-range GET 왕복을 제거하기 위해
  // 자동 측정 흐름의 mutation onSuccess 도 react-query 캐시를 prime 한다.
  it("저장 성공 시 응답을 react-query 캐시에 prime 한다 (#282)", async () => {
    const user = userEvent.setup();
    const response = {
      id: 92,
      sessionId: "test-session-id",
      lowestNoteMidi: 48,
      highestNoteMidi: 69,
      sourceMethod: "MIC_MEASURE" as const,
      createdAt: "2026-05-22T00:00:00Z",
      updatedAt: "2026-05-22T00:00:00Z",
    };
    createVoiceRangeMock.mockResolvedValueOnce(response);

    const { client } = renderWithExposedQueryClient(
      <AutoVoiceRangePage deps={buildDeps()} />,
    );
    await user.click(screen.getByRole("button", { name: /측정 시작/ }));

    await waitFor(() => {
      expect(
        screen.getByRole("heading", { name: /측정 결과/ }),
      ).toBeInTheDocument();
    });

    await user.click(screen.getByRole("button", { name: /추천 받기/ }));

    await waitFor(() => {
      expect(pushMock).toHaveBeenCalledWith("/recommend");
    });

    expect(client.getQueryData(["voice-range", "test-session-id"])).toEqual(
      response,
    );
  });

  it("API 실패 시 에러 메시지를 노출하고 라우팅하지 않는다", async () => {
    const user = userEvent.setup();
    createVoiceRangeMock.mockRejectedValueOnce(
      new ApiError(500, "server boom", { message: "server boom" }),
    );

    renderWithQueryClient(<AutoVoiceRangePage deps={buildDeps()} />);
    await user.click(screen.getByRole("button", { name: /측정 시작/ }));

    await waitFor(() => {
      expect(
        screen.getByRole("heading", { name: /측정 결과/ }),
      ).toBeInTheDocument();
    });

    await user.click(screen.getByRole("button", { name: /추천 받기/ }));

    await waitFor(() => {
      expect(screen.getByText(/저장에 실패했습니다/)).toBeInTheDocument();
    });
    expect(pushMock).not.toHaveBeenCalled();
  });
});

// PR #1029 (`/voice-range/page.test.tsx`) 가 raw `useMutation(createVoiceRange)`
// 의 race/unmount/Button reflect 가드를 깔았다. `/voice-range/auto` 페이지는
// 같은 mutation 외에 마이크 + handleRetry + fallbackTimerRef 까지 더 복잡한
// race state 를 가지므로 동일 패턴을 확장한다 (closes #1029 후속).
//
// 검증 범위:
//   1) 저장 mutation 빠른 연속 클릭 race
//      - "추천 받기" 버튼은 mutation.isPending 동안 loading=true 로 disabled.
//      - 같은 turn 안에 두 번째 클릭해도 createVoiceRange 는 1회만 호출.
//      - 페이지 본체의 `loading={mutation.isPending}` 회귀 가드.
//
//   2) 저장 버튼 isPending 동안 UI reflect (aria-busy / disabled / "저장 중..." 라벨)
//      - 셋 중 하나라도 회귀로 빠지면 더블 submit 가능.
//      - 동시에 "다시 측정하기" 버튼도 `disabled={saving}` 로 잠겨야 한다.
//
//   3) 저장 mutation pending 중 unmount → React state update warning 0건
//      - mutationFn pending 도중 unmount() → resolve 시 console.error 0건.
//
//   4) 401 응답 시 alert 영역에 에러 노출 + /recommend 라우팅 차단
//      - `ApiError(401, ...)` reject → "저장에 실패했습니다 401: ..." 표시.
//      - pushMock / setVoiceRangeId 호출 0건.
//
//   5) fallback timer race — handleRetry 가 pending fallback timer 를 정리
//      - 권한 거부 catch 가 setTimeout(1.2s → /voice-range push) 를 띄움.
//      - timer fire 전 "다시 측정하기" 또는 unmount 가 일어나면 push 가 호출되면 안 됨.
//      - (#173 시나리오 — 살아있던 timer 가 사용자를 PERMISSION 밖으로 튕기는 회귀 가드)
//
// 비범위:
//   - 페이지 본체 시그니처 / 동작 변경 없음 (테스트만 추가).
//   - `npm run build` 는 본 워크트리 symlink 이슈로 skip (PR #1029 동일).
describe("AutoVoiceRangePage mutation 경계 가드 (race/unmount/401/fallback timer)", () => {
  /**
   * mutationFn 응답 시점을 테스트가 직접 통제할 수 있게 하는 deferred.
   * race / unmount 케이스에서 pending 상태를 임의 길이로 유지한다.
   */
  function createDeferred<T>(): {
    promise: Promise<T>;
    resolve: (value: T) => void;
    reject: (reason: unknown) => void;
  } {
    let resolve!: (value: T) => void;
    let reject!: (reason: unknown) => void;
    const promise = new Promise<T>((res, rej) => {
      resolve = res;
      reject = rej;
    });
    return { promise, resolve, reject };
  }

  type VoiceRangeResponseShape = {
    id: number;
    sessionId: string;
    lowestNoteMidi: number;
    highestNoteMidi: number;
    sourceMethod: "MIC_MEASURE";
    createdAt: string;
    updatedAt: string;
  };

  /**
   * RESULT 단계까지 진입한 페이지를 렌더해서 "추천 받기" 버튼 reference 를 돌려준다.
   * 본 describe 의 모든 케이스는 RESULT 단계에서 save mutation 을 트리거한다.
   */
  async function renderUntilResult(
    deps = buildDeps(),
  ): Promise<{ submit: HTMLButtonElement; unmount: () => void }> {
    const user = userEvent.setup();
    const { unmount } = renderWithQueryClient(
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
    return { submit, unmount };
  }

  it("빠른 연속 클릭 시 createVoiceRange 는 1회만 호출된다 (Button loading 가드)", async () => {
    const user = userEvent.setup();
    const deferred = createDeferred<VoiceRangeResponseShape>();
    createVoiceRangeMock.mockReturnValueOnce(deferred.promise);

    const { submit } = await renderUntilResult();
    await user.click(submit);

    // 첫 호출 후 isPending=true → Button disabled.
    await waitFor(() => {
      expect(createVoiceRangeMock).toHaveBeenCalledTimes(1);
    });
    await waitFor(() => {
      expect(submit).toBeDisabled();
    });

    // 같은 turn 안에 두 번째 클릭 — Button disabled 로 차단.
    await user.click(submit);
    expect(createVoiceRangeMock).toHaveBeenCalledTimes(1);

    // pending 해제 → router.push 까지 act 안에서 마무리해 act warning 회피.
    await act(async () => {
      deferred.resolve({
        id: 1001,
        sessionId: "test-session-id",
        lowestNoteMidi: 48,
        highestNoteMidi: 69,
        sourceMethod: "MIC_MEASURE",
        createdAt: "2026-05-24T00:00:00Z",
        updatedAt: "2026-05-24T00:00:00Z",
      });
      await deferred.promise;
    });
  });

  it("mutation pending 동안 '추천 받기' 가 aria-busy + disabled + '저장 중...' 라벨로 reflect 되고 '다시 측정하기' 도 잠긴다", async () => {
    const user = userEvent.setup();
    const deferred = createDeferred<VoiceRangeResponseShape>();
    createVoiceRangeMock.mockReturnValueOnce(deferred.promise);

    const { submit } = await renderUntilResult();
    const retry = screen.getByRole("button", { name: /다시 측정하기/ });

    // 클릭 전 baseline: 추천 받기 enabled, aria-busy 없음, "추천 받기" 라벨.
    expect(submit).toBeEnabled();
    expect(submit).not.toHaveAttribute("aria-busy", "true");
    expect(submit).toHaveTextContent(/추천 받기/);
    expect(retry).toBeEnabled();

    await user.click(submit);

    // 추천 받기 — 3 항목 동시 reflect.
    await waitFor(() => {
      expect(submit).toBeDisabled();
    });
    expect(submit).toHaveAttribute("aria-busy", "true");
    expect(submit).toHaveTextContent(/저장 중\.\.\./);

    // 다시 측정하기 — 저장 중에는 잠겨야 한다 (race 진입 차단).
    expect(retry).toBeDisabled();

    // 응답 도착 → cleanup.
    await act(async () => {
      deferred.resolve({
        id: 1002,
        sessionId: "test-session-id",
        lowestNoteMidi: 48,
        highestNoteMidi: 69,
        sourceMethod: "MIC_MEASURE",
        createdAt: "2026-05-24T00:00:00Z",
        updatedAt: "2026-05-24T00:00:00Z",
      });
      await deferred.promise;
    });
  });

  it("저장 mutation pending 중 unmount 해도 React state update warning 이 발생하지 않는다", async () => {
    const user = userEvent.setup();
    const deferred = createDeferred<VoiceRangeResponseShape>();
    createVoiceRangeMock.mockReturnValueOnce(deferred.promise);

    const consoleErrorSpy = vi
      .spyOn(console, "error")
      .mockImplementation(() => {});

    const { submit, unmount } = await renderUntilResult();
    await user.click(submit);

    await waitFor(() => {
      expect(createVoiceRangeMock).toHaveBeenCalledTimes(1);
    });

    // mutation pending 중 컴포넌트 unmount.
    unmount();

    // 응답 도착 — unmounted observer 의 setState 가 무시되어야 한다.
    await act(async () => {
      deferred.resolve({
        id: 1003,
        sessionId: "test-session-id",
        lowestNoteMidi: 48,
        highestNoteMidi: 69,
        sourceMethod: "MIC_MEASURE",
        createdAt: "2026-05-24T00:00:00Z",
        updatedAt: "2026-05-24T00:00:00Z",
      });
      await deferred.promise;
    });

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

  it("저장 401 응답 시 에러 메시지를 노출하고 /recommend 라우팅하지 않는다", async () => {
    const user = userEvent.setup();
    createVoiceRangeMock.mockRejectedValueOnce(
      new ApiError(401, "Unauthorized", { error: "auth" }),
    );

    const { submit } = await renderUntilResult();
    await user.click(submit);

    // 페이지가 submitError 를 "<status>: <message>" 형식으로 노출한다.
    await waitFor(() => {
      expect(screen.getByText(/저장에 실패했습니다/)).toBeInTheDocument();
    });
    expect(screen.getByText(/401: Unauthorized/)).toBeInTheDocument();

    // 401 은 onSuccess 가 호출되지 않으므로 push / setVoiceRangeId 가 0건.
    expect(pushMock).not.toHaveBeenCalledWith("/recommend");
    expect(sessionMock.state().setVoiceRangeId).not.toHaveBeenCalled();
  });

  // 권한 거부 시 페이지가 setTimeout(1.2s → router.push("/voice-range")) 를 띄운다.
  // 사용자가 timer fire 전 컴포넌트를 unmount 하면 (예: 다른 페이지로 navigate)
  // useEffect cleanup 이 timer 를 clear 해 push 가 호출되면 안 된다.
  //
  // 회귀 가드: cleanup 에서 fallbackTimerRef clear 가 빠지면 unmount 후에도
  // 1.2초 뒤 push 가 실행되어 사용자가 의도치 않게 /voice-range 로 끌려간다.
  it("권한 거부 fallback timer 가 fire 전 unmount 되면 /voice-range push 가 호출되지 않는다", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    const notAllowed = Object.assign(new Error("denied"), {
      name: "NotAllowedError",
    });
    const deps = buildDeps({
      requestMic: vi.fn().mockRejectedValue(notAllowed),
    });
    const { unmount } = renderWithQueryClient(
      <AutoVoiceRangePage deps={deps} />,
    );

    await user.click(screen.getByRole("button", { name: /측정 시작/ }));

    // 권한 거부 alert 노출 (= fallback timer 가 등록된 시점).
    await waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent(
        /마이크 권한이 거부되었습니다/,
      );
    });
    expect(pushMock).not.toHaveBeenCalledWith("/voice-range");

    // timer fire 전에 unmount.
    unmount();

    // 1.2초 + α 시간을 흘려도 push 가 호출되지 않아야 한다.
    await act(async () => {
      vi.advanceTimersByTime(1500);
    });
    expect(pushMock).not.toHaveBeenCalledWith("/voice-range");

    vi.useRealTimers();
  });
});

describe("AutoVoiceRangePage cleanup (#404)", () => {
  // #404 A-1 회귀 가드: 측정 중 unmount → runPhase 의 AbortSignal 로 sampler 가
  // 즉시 종료되어야 한다. signal 미주입 회귀가 생기면 이 테스트가 실패한다.
  it("측정 중 unmount 하면 runPhase 에 전달된 AbortSignal 이 abort 된다", async () => {
    const user = userEvent.setup();
    let capturedSignal: AbortSignal | undefined;
    const deps = buildDeps({
      runPhase: vi.fn().mockImplementation((_phase, _stream, onSample, signal) => {
        capturedSignal = signal;
        // low phase 가 진행 중인 상태로 매달려 있게 둔다 → unmount 가 가능해진다.
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

    const { unmount } = renderWithQueryClient(
      <AutoVoiceRangePage deps={deps} />,
    );
    await user.click(screen.getByRole("button", { name: /측정 시작/ }));

    // runPhase 호출되어 signal 이 캡처될 때까지 대기.
    await waitFor(() => {
      expect(capturedSignal).toBeDefined();
    });
    expect(capturedSignal!.aborted).toBe(false);

    unmount();

    // unmount cleanup 에서 controller.abort() 가 호출되어야 한다.
    expect(capturedSignal!.aborted).toBe(true);
  });

  // #404 A-2 회귀 가드: requestMic 성공 후 runPhase throw 시 catch 진입 즉시
  // 마이크 stream.stop() 이 호출되어야 한다. 이전 구현은 unmount cleanup 까지
  // 약 1.2 초간 마이크 활성이 유지됐다.
  it("측정 phase 실패 시 catch 에서 stream track.stop() 이 즉시 호출된다", async () => {
    const user = userEvent.setup();
    const trackStop = vi.fn();
    const stream = {
      getTracks: () => [{ stop: trackStop } as unknown as MediaStreamTrack],
    } as unknown as MediaStream;
    const deps = buildDeps({
      requestMic: vi.fn().mockResolvedValue(stream),
      runPhase: vi.fn().mockRejectedValue(new Error("analyser boom")),
    });

    renderWithQueryClient(<AutoVoiceRangePage deps={deps} />);
    await user.click(screen.getByRole("button", { name: /측정 시작/ }));

    // catch 블록에서 stream.getTracks().forEach(stop) 가 호출되어야 한다.
    // 이전 구현은 1.2초 redirect 까지 stop 미호출 — 본 테스트가 즉시 동기적으로
    // (await catch 후) 호출됨을 검증한다.
    await waitFor(() => {
      expect(trackStop).toHaveBeenCalledTimes(1);
    });
  });
});

describe("AutoVoiceRangePage a11y", () => {
  it("초기 권한 화면에 a11y 위반이 없다", async () => {
    const { container } = renderWithQueryClient(
      <AutoVoiceRangePage deps={buildDeps()} />,
    );
    await expectNoA11yViolations(container);
  });

  // #454: 단계 전환 announce 를 단일 status region 으로 일원화. 외곽 wrapper
  // aria-live 와 nested MeasureStep aria-live 중첩 회귀를 막는다.
  it("PERMISSION 단계에서 status region 이 권한 안내 텍스트를 노출한다", () => {
    renderWithQueryClient(<AutoVoiceRangePage deps={buildDeps()} />);
    expect(screen.getByTestId("auto-step-status")).toHaveTextContent(
      "마이크 권한 안내 화면입니다.",
    );
  });

  it("측정 흐름이 RESULT 까지 진행되면 status region 이 완료 안내로 갱신된다", async () => {
    const user = userEvent.setup();
    renderWithQueryClient(<AutoVoiceRangePage deps={buildDeps()} />);

    await user.click(screen.getByRole("button", { name: /측정 시작/ }));

    await waitFor(() => {
      expect(
        screen.getByRole("heading", { name: /측정 결과/ }),
      ).toBeInTheDocument();
    });

    expect(screen.getByTestId("auto-step-status")).toHaveTextContent(
      "측정이 완료되었습니다. 결과를 확인하세요.",
    );
  });
});
