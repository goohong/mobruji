/**
 * PitchWaveRing 컴포넌트 테스트 — ui-ux-redesign-pr-5-pitch-wave.md §5-3 회귀 가드.
 *
 * 결정성(G1~G3)은 helpers.test.ts 가 담당. 본 파일은 렌더 의존 가드:
 *   G4  aria-label 동적 갱신(debounce 500ms)
 *   G5  reduced-motion → animation 클래스 미부착(단 dot 위치 즉시 갱신 유지)
 *   G9  previousRangeMidi outline 조건부 표시
 *   G10 step indicator aria-current="step"
 *   G11 ring/dot/bar 토큰 사용(다크 모드 자동 swap)
 *   G12 progressPercent milestone 도달 시 sparkle 부착
 */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";

import { PitchWaveRing } from "./PitchWaveRing";

function mockMatchMedia(matchReduced: boolean) {
  vi.stubGlobal(
    "matchMedia",
    vi.fn().mockImplementation((query: string) => ({
      matches: query.includes("prefers-reduced-motion") ? matchReduced : false,
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  );
}

beforeEach(() => {
  mockMatchMedia(false);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});

describe("PitchWaveRing 기본 렌더", () => {
  it("svg role=img + base/progress ring + frequency bar 를 렌더한다", () => {
    render(
      <PitchWaveRing
        lowMidi={36}
        highMidi={84}
        currentFrequencyHz={null}
        progressPercent={0}
      />,
    );
    expect(screen.getByRole("img")).toBeInTheDocument();
    expect(screen.getByTestId("pitch-ring-base")).toBeInTheDocument();
    expect(screen.getByTestId("pitch-ring-progress")).toBeInTheDocument();
    expect(screen.getByTestId("pitch-frequency-bar")).toBeInTheDocument();
  });

  it("currentFrequencyHz 가 null 이면 indicator dot 을 숨긴다", () => {
    render(
      <PitchWaveRing
        lowMidi={36}
        highMidi={84}
        currentFrequencyHz={null}
        progressPercent={0}
      />,
    );
    expect(screen.queryByTestId("pitch-indicator-dot")).not.toBeInTheDocument();
  });

  it("currentFrequencyHz 가 유효하면 indicator dot 을 표시한다", () => {
    render(
      <PitchWaveRing
        lowMidi={36}
        highMidi={84}
        currentFrequencyHz={261.63}
        progressPercent={30}
      />,
    );
    expect(screen.getByTestId("pitch-indicator-dot")).toBeInTheDocument();
  });
});

describe("aria-label 동적 갱신 debounce (G4)", () => {
  it("currentFrequencyHz 변경 시 aria-label 이 debounce(500ms) 후 갱신된다", async () => {
    const { rerender } = render(
      <PitchWaveRing
        lowMidi={36}
        highMidi={84}
        currentFrequencyHz={null}
        progressPercent={0}
      />,
    );
    // 초기 라벨 — 측정 대기.
    expect(screen.getByRole("img")).toHaveAttribute(
      "aria-label",
      expect.stringContaining("음정 측정 대기"),
    );

    // 음정 입력 — debounce 후 현재 음정이 반영된다.
    rerender(
      <PitchWaveRing
        lowMidi={36}
        highMidi={84}
        currentFrequencyHz={440}
        progressPercent={50}
      />,
    );
    await waitFor(
      () => {
        expect(screen.getByRole("img").getAttribute("aria-label")).toMatch(
          /현재 음정 2옥라/,
        );
      },
      { timeout: 1500 },
    );
    expect(screen.getByRole("img").getAttribute("aria-label")).toMatch(/50%/);
  });
});

describe("reduced-motion (G5)", () => {
  it("reduce 설정 시 sparkle/flash 에 animation 클래스를 부착하지 않는다", () => {
    mockMatchMedia(true);
    render(
      <PitchWaveRing
        lowMidi={36}
        highMidi={84}
        currentFrequencyHz={440}
        progressPercent={100}
      />,
    );
    const sparkle = screen.getByTestId("pitch-sparkle-50");
    // 안쪽 g 에 animate 클래스가 없어야 한다(전역 CSS 가드 + JS 분기 이중).
    expect(sparkle.querySelector(".animate-pitch-sparkle")).toBeNull();

    const flash = screen.getByTestId("pitch-brand-flash");
    expect(flash.className).not.toContain("animate-brand-flash");
  });

  it("reduce 설정이어도 indicator dot 위치는 즉시 갱신된다 (pitch 정보 본질)", () => {
    mockMatchMedia(true);
    render(
      <PitchWaveRing
        lowMidi={36}
        highMidi={84}
        currentFrequencyHz={440}
        progressPercent={50}
      />,
    );
    // dot 은 여전히 렌더된다 — reduce 라고 음정 위치 표시를 끄지 않는다.
    expect(screen.getByTestId("pitch-indicator-dot")).toBeInTheDocument();
  });

  it("모션 허용 시 sparkle 에 animation 클래스를 부착한다", () => {
    mockMatchMedia(false);
    render(
      <PitchWaveRing
        lowMidi={36}
        highMidi={84}
        currentFrequencyHz={440}
        progressPercent={100}
      />,
    );
    const sparkle = screen.getByTestId("pitch-sparkle-50");
    expect(sparkle.querySelector(".animate-pitch-sparkle")).not.toBeNull();
  });
});

describe("previousRangeMidi outline (G9)", () => {
  it("previousRangeMidi 가 없으면 outline 을 표시하지 않는다", () => {
    render(
      <PitchWaveRing
        lowMidi={36}
        highMidi={84}
        currentFrequencyHz={null}
        progressPercent={0}
      />,
    );
    expect(screen.queryByTestId("pitch-ring-previous")).not.toBeInTheDocument();
  });

  it("previousRangeMidi 가 있으면 점선 outline 을 표시한다", () => {
    render(
      <PitchWaveRing
        lowMidi={36}
        highMidi={84}
        currentFrequencyHz={null}
        progressPercent={0}
        previousRangeMidi={[43, 66]}
      />,
    );
    const outline = screen.getByTestId("pitch-ring-previous");
    expect(outline).toBeInTheDocument();
    expect(outline).toHaveAttribute("stroke-dasharray", "4 4");
  });
});

describe("step indicator (G10)", () => {
  it("aria-current=step + step number / 안내 텍스트를 표시한다", () => {
    render(
      <PitchWaveRing
        lowMidi={36}
        highMidi={84}
        currentFrequencyHz={null}
        progressPercent={0}
        stepNumber={1}
        totalSteps={2}
        stepLabel="편안한 음으로 아~ 발성해보세요"
      />,
    );
    const indicator = screen.getByTestId("pitch-step-indicator");
    expect(indicator).toHaveAttribute("aria-current", "step");
    expect(indicator).toHaveTextContent("1");
    expect(indicator).toHaveTextContent("/ 2");
    expect(
      screen.getByText("편안한 음으로 아~ 발성해보세요"),
    ).toBeInTheDocument();
  });
});

describe("토큰 사용 — 다크 모드 자동 swap (G11)", () => {
  it("ring/dot/bar 가 CSS var 토큰을 사용한다", () => {
    render(
      <PitchWaveRing
        lowMidi={36}
        highMidi={84}
        currentFrequencyHz={440}
        progressPercent={20}
        amplitude={0.8}
      />,
    );
    expect(screen.getByTestId("pitch-ring-base")).toHaveAttribute(
      "stroke",
      "var(--bg-subtle)",
    );
    expect(screen.getByTestId("pitch-ring-progress")).toHaveAttribute(
      "stroke",
      "var(--brand-500)",
    );
    // amplitude 0.8 → 강한 강도 토큰.
    expect(screen.getByTestId("pitch-indicator-dot")).toHaveAttribute(
      "fill",
      "var(--brand-700)",
    );
  });
});

describe("sparkle milestone (G12)", () => {
  it("progressPercent 0 이면 sparkle 가 하나도 없다", () => {
    render(
      <PitchWaveRing
        lowMidi={36}
        highMidi={84}
        currentFrequencyHz={null}
        progressPercent={0}
      />,
    );
    expect(screen.queryByTestId("pitch-sparkle-25")).not.toBeInTheDocument();
    expect(screen.queryByTestId("pitch-sparkle-100")).not.toBeInTheDocument();
  });

  it("progressPercent 50 이면 25/50 sparkle 만 부착되고 75/100 은 미부착", () => {
    render(
      <PitchWaveRing
        lowMidi={36}
        highMidi={84}
        currentFrequencyHz={null}
        progressPercent={50}
      />,
    );
    expect(screen.getByTestId("pitch-sparkle-25")).toBeInTheDocument();
    expect(screen.getByTestId("pitch-sparkle-50")).toBeInTheDocument();
    expect(screen.queryByTestId("pitch-sparkle-75")).not.toBeInTheDocument();
    expect(screen.queryByTestId("pitch-sparkle-100")).not.toBeInTheDocument();
  });

  it("progressPercent 100 이면 모든 sparkle + brand flash 가 표시된다", () => {
    render(
      <PitchWaveRing
        lowMidi={36}
        highMidi={84}
        currentFrequencyHz={null}
        progressPercent={100}
      />,
    );
    expect(screen.getByTestId("pitch-sparkle-100")).toBeInTheDocument();
    expect(screen.getByTestId("pitch-brand-flash")).toBeInTheDocument();
  });

  it("progressPercent 100 미만이면 brand flash 가 없다", () => {
    render(
      <PitchWaveRing
        lowMidi={36}
        highMidi={84}
        currentFrequencyHz={null}
        progressPercent={99}
      />,
    );
    expect(screen.queryByTestId("pitch-brand-flash")).not.toBeInTheDocument();
  });
});
