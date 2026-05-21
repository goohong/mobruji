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

    // 측정 결과: low=48(C3), high=69(A4) 가 슬라이더 표시값에 반영.
    expect(screen.getByText(/C3 \(MIDI 48\)/)).toBeInTheDocument();
    expect(screen.getByText(/A4 \(MIDI 69\)/)).toBeInTheDocument();
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

    expect(screen.getByText(/D3 \(MIDI 50\)/)).toBeInTheDocument();
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

describe("AutoVoiceRangePage a11y", () => {
  it("초기 권한 화면에 a11y 위반이 없다", async () => {
    const { container } = renderWithQueryClient(
      <AutoVoiceRangePage deps={buildDeps()} />,
    );
    await expectNoA11yViolations(container);
  });
});
