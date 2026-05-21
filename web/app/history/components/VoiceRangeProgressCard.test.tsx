/**
 * VoiceRangeProgressCard 렌더 테스트 (closes #170).
 */

import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

import { VoiceRangeProgressCard } from "./VoiceRangeProgressCard";
import { expectNoA11yViolations } from "@/lib/test-helpers/a11y";
import type { VoiceRangeProgressSummary } from "@/lib/voiceRangeProgress";

function buildSummary(
  overrides: Partial<VoiceRangeProgressSummary> = {},
): VoiceRangeProgressSummary {
  // 시간 오름차순(과거 → 현재) datapoint 3개.
  return {
    points: [
      {
        id: "p-1",
        requestedAt: "2026-05-21T08:00:00Z",
        lowMidi: 52,
        highMidi: 70,
        voiceRangeId: 1,
        sourceMethod: "SELF_REPORT",
      },
      {
        id: "p-2",
        requestedAt: "2026-05-21T10:00:00Z",
        lowMidi: 51,
        highMidi: 72,
        voiceRangeId: 2,
        sourceMethod: "SELF_REPORT",
      },
      {
        id: "p-3",
        requestedAt: "2026-05-21T12:00:00Z",
        lowMidi: 50,
        highMidi: 74,
        voiceRangeId: 3,
        sourceMethod: "MIC_MEASURE",
      },
    ],
    minLowMidi: 50,
    maxHighMidi: 74,
    latestSpanSemitones: 24,
    earliestSpanSemitones: 18,
    spanDeltaSemitones: 6,
    ...overrides,
  };
}

afterEach(() => {
  cleanup();
});

describe("VoiceRangeProgressCard", () => {
  it("음역폭이 넓어진 경우 헤드라인과 최신 측정 음표명을 노출한다", () => {
    render(<VoiceRangeProgressCard summary={buildSummary()} />);

    // spanDelta = +6 → 넓어졌어요 헤드라인.
    expect(
      screen.getByRole("heading", { name: /\+6 반음 넓어졌어요/ }),
    ).toBeInTheDocument();
    // 최신 측정 50~74 → 음표명 D3 ~ D5 가 헤더 + svg title 두 곳에 노출.
    // (MIDI 50 = D3, 74 = D5; midiToNoteName 사용)
    expect(screen.getAllByText(/D3 ~ D5/).length).toBeGreaterThanOrEqual(1);
    // 3회 측정 기록 안내 (헤더 1곳).
    expect(screen.getByText(/3회 측정 기록/)).toBeInTheDocument();
  });

  it("음역폭이 동일하게 유지된 경우 '유지되고 있어요' 헤드라인을 보인다", () => {
    const summary = buildSummary({
      spanDeltaSemitones: 0,
      latestSpanSemitones: 18,
      earliestSpanSemitones: 18,
    });
    render(<VoiceRangeProgressCard summary={summary} />);

    expect(
      screen.getByRole("heading", { name: /음역폭이 18 반음으로 유지/ }),
    ).toBeInTheDocument();
  });

  it("SVG 차트는 role=img + aria-label 로 요약 정보를 제공한다 (a11y)", async () => {
    const { container } = render(
      <VoiceRangeProgressCard summary={buildSummary()} />,
    );

    const chart = screen.getByRole("img");
    expect(chart.tagName.toLowerCase()).toBe("svg");
    expect(chart.getAttribute("aria-label")).toMatch(/음역 발전 차트/);
    expect(chart.getAttribute("aria-label")).toMatch(/6반음 넓어짐/);

    await expectNoA11yViolations(container);
  });
});
