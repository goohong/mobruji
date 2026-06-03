/**
 * VoiceRangeScale 렌더 테스트 (voice-range-intuitive-display.md §7).
 *
 * 좁은/넓은/극단 음역에서 밴드·기준선 렌더, role="img" + aria-label 요약,
 * 벤치마크 overlay, 비유한 입력 fallback 을 검증한다.
 */

import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

import { VoiceRangeScale } from "./VoiceRangeScale";
import { NEUTRAL_BENCHMARK } from "@/lib/voiceRangeBenchmark";
import { expectNoA11yViolations } from "@/lib/test-helpers/a11y";

afterEach(() => {
  cleanup();
});

describe("VoiceRangeScale", () => {
  it("role=img + aria-label 로 내 음역대/평균 음역대를 요약한다", () => {
    render(<VoiceRangeScale lowMidi={48} highMidi={69} />);
    const img = screen.getByRole("img");
    const label = img.getAttribute("aria-label") ?? "";
    expect(label).toContain("내 음역대");
    expect(label).toContain("평균 음역대");
    expect(label).toContain("C3"); // low 48
    expect(label).toContain("A4"); // high 69
  });

  it("사용자 밴드와 벤치마크 밴드를 모두 렌더한다", () => {
    const { container } = render(
      <VoiceRangeScale lowMidi={48} highMidi={69} />,
    );
    expect(
      container.querySelector(
        '[data-testid="voice-range-scale-user-band"]',
      ),
    ).not.toBeNull();
    expect(
      container.querySelector(
        '[data-testid="voice-range-scale-benchmark-band"]',
      ),
    ).not.toBeNull();
  });

  it("사용자 밴드 width 는 음역 폭에 비례한다 (넓을수록 큼)", () => {
    const narrow = render(<VoiceRangeScale lowMidi={58} highMidi={60} />);
    const narrowWidth = Number(
      narrow.container
        .querySelector('[data-testid="voice-range-scale-user-band"]')
        ?.getAttribute("width"),
    );
    cleanup();
    const wide = render(<VoiceRangeScale lowMidi={40} highMidi={72} />);
    const wideWidth = Number(
      wide.container
        .querySelector('[data-testid="voice-range-scale-user-band"]')
        ?.getAttribute("width"),
    );
    expect(wideWidth).toBeGreaterThan(narrowWidth);
  });

  it("좁은 음역(2반음)에서도 밴드가 최소 폭으로 보인다", () => {
    const { container } = render(
      <VoiceRangeScale lowMidi={59} highMidi={60} />,
    );
    const width = Number(
      container
        .querySelector('[data-testid="voice-range-scale-user-band"]')
        ?.getAttribute("width"),
    );
    expect(width).toBeGreaterThanOrEqual(2);
  });

  it("caption 을 figcaption 으로 노출한다", () => {
    render(
      <VoiceRangeScale lowMidi={48} highMidi={69} caption="내 음역대 한눈에" />,
    );
    expect(screen.getByText("내 음역대 한눈에")).toBeTruthy();
  });

  it("비유한 입력은 시각화 대신 안내 텍스트로 대체한다", () => {
    render(<VoiceRangeScale lowMidi={NaN} highMidi={60} />);
    expect(screen.queryByRole("img")).toBeNull();
    expect(screen.getByText("음정 정보 없음")).toBeTruthy();
  });

  it("커스텀 벤치마크를 받으면 그 범위를 aria-label 에 반영한다", () => {
    render(
      <VoiceRangeScale
        lowMidi={55}
        highMidi={74}
        benchmark={NEUTRAL_BENCHMARK}
      />,
    );
    const label = screen.getByRole("img").getAttribute("aria-label") ?? "";
    // NEUTRAL low 45 = A2, high 60 = C4
    expect(label).toContain("A2");
    expect(label).toContain("C4");
  });

  it("a11y 위반(serious/critical) 이 없다", async () => {
    const { container } = render(
      <VoiceRangeScale lowMidi={48} highMidi={69} caption="내 음역대" />,
    );
    await expectNoA11yViolations(container);
  });
});
