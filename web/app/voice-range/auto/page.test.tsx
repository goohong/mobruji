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
});
