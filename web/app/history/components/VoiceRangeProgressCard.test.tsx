/**
 * VoiceRangeProgressCard 렌더 테스트 (closes #170 / #249).
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
    // earliest 52~70 → latest 50~74: lowMidi -2(낮아짐), highMidi +4(높아짐).
    lowMidiDeltaSemitones: -2,
    highMidiDeltaSemitones: 4,
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

  it("#249: 모든 측정 막대에 lowMidi/highMidi 음표명 라벨이 노출된다", () => {
    // 기본 픽스처: 52~70 / 51~72 / 50~74.
    // → low/high 음표: E3/A#4, D#3/C5, D3/D5.
    const { container } = render(
      <VoiceRangeProgressCard summary={buildSummary()} />,
    );

    // SVG <text> 요소 검사 — DOM textContent 매칭으로 음표명 등장 횟수 확인.
    const svgTexts = Array.from(
      container.querySelectorAll("svg text"),
    ).map((node) => node.textContent ?? "");

    // 1번 측정: low=E3, high=A#4
    expect(svgTexts).toContain("E3");
    expect(svgTexts).toContain("A#4");
    // 2번 측정: low=D#3, high=C5
    expect(svgTexts).toContain("D#3");
    expect(svgTexts).toContain("C5");
    // 3번(최신) 측정: low=D3, high=D5
    expect(svgTexts).toContain("D3");
    expect(svgTexts).toContain("D5");
  });

  it("#249: Y축에 옥타브 시작음(C-노트) 그리드 라벨이 표시된다", () => {
    // 픽스처 범위 50(D3) ~ 74(D5) → C4(60), C5(72) 가 가시 범위 내 옥타브.
    const { container } = render(
      <VoiceRangeProgressCard summary={buildSummary()} />,
    );

    const svgTexts = Array.from(
      container.querySelectorAll("svg text"),
    ).map((node) => node.textContent ?? "");

    expect(svgTexts).toContain("C4");
    expect(svgTexts).toContain("C5");
  });

  it("#567: points 가 빈 배열이면 role=status 안내 메시지만 노출하고 차트는 그리지 않는다", () => {
    // 부모(page.tsx)가 EmptyHistory 분기를 보장하지만, 회귀 방지 차원의 방어 가드.
    const empty = buildSummary({
      points: [],
      minLowMidi: 0,
      maxHighMidi: 0,
      latestSpanSemitones: 0,
      earliestSpanSemitones: 0,
      spanDeltaSemitones: 0,
      lowMidiDeltaSemitones: 0,
      highMidiDeltaSemitones: 0,
    });
    render(<VoiceRangeProgressCard summary={empty} />);

    const status = screen.getByRole("status");
    expect(status).toHaveTextContent(/측정 기록이 없어요/);
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
  });

  it("#249: 가시 범위에 옥타브 시작음이 전혀 없으면 가장 가까운 C-노트 1개를 표시한다 (fallback)", () => {
    // D4(62) ~ G4(67) — 차트 가시 범위 yMin=60,yMax=69 에 C4(60)는 경계 위에 있으므로
    // 'fallback path' 가 아닌 정상 후보 path 가 실행된다.
    // → fallback path 단독을 확인하기 위해 D5~G5 (62+12=74 ~ 79) 범위로 옮긴다:
    //   yMin=72(C5), yMax=81 → C5(72) 포함 → fallback 미발동.
    // 진짜로 가시 범위에 어떤 C-노트도 없는 케이스는 음역이 12반음 미만이면서 양 끝이
    // C-노트와 정확히 일치하지 않는 경우. 예: D#5(75)~A5(81) → yMin=73, yMax=83 → C-노트
    // 없음(72 외부, 84 외부) → fallback 발동, nearest C = round(78/12)*12 = 84(C6).
    const narrow = buildSummary({
      points: [
        {
          id: "n-1",
          requestedAt: "2026-05-21T08:00:00Z",
          lowMidi: 75,
          highMidi: 79,
          voiceRangeId: 1,
          sourceMethod: "SELF_REPORT",
        },
        {
          id: "n-2",
          requestedAt: "2026-05-21T10:00:00Z",
          lowMidi: 75,
          highMidi: 81,
          voiceRangeId: 2,
          sourceMethod: "SELF_REPORT",
        },
      ],
      minLowMidi: 75,
      maxHighMidi: 81,
      latestSpanSemitones: 6,
      earliestSpanSemitones: 4,
      spanDeltaSemitones: 2,
      lowMidiDeltaSemitones: 0,
      highMidiDeltaSemitones: 2,
    });
    const { container } = render(<VoiceRangeProgressCard summary={narrow} />);
    const svgTexts = Array.from(
      container.querySelectorAll("svg text"),
    ).map((node) => node.textContent ?? "");

    // yMin=73, yMax=83 → C-노트(60·72·84) 모두 외부 → fallback nearest:
    // round((73+83)/2 / 12) * 12 = round(78/12)*12 = round(6.5)*12 = 7*12 = 84 = C6.
    expect(svgTexts).toContain("C6");
  });
});
