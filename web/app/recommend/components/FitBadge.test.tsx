/**
 * FitBadge / FitReasons 컴포넌트 테스트 (이슈 #1484).
 *
 * 검증 포인트:
 *   1) FitBadge: 라벨 + 퍼센트 aria-label 노출.
 *   2) FitReasons: voiceFit/moodFit 둘 다 있으면 배지 + 한국어 사유 노출.
 *   3) FitReasons: 적합도 점수가 없는(과거 추천) 경우 아무것도 그리지 않는다(null).
 *   4) FitReasons: 점수는 있으나 사유가 null 이면 배지만, 사유 문장은 생략.
 */

import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

import { buildRecommendedSong } from "@/lib/test-fixtures/history";

import { FitBadge, FitReasons } from "./FitBadge";

afterEach(() => cleanup());

describe("FitBadge", () => {
  it("라벨 + 퍼센트를 aria-label 로 노출한다", () => {
    render(<FitBadge label="음역 적합도" fit={0.82} />);
    expect(
      screen.getByLabelText("음역 적합도 82%"),
    ).toBeInTheDocument();
  });
});

describe("FitReasons", () => {
  it("voiceFit/moodFit + 사유를 모두 노출한다", () => {
    const item = buildRecommendedSong({
      voiceFit: 0.9,
      voiceFitReason: "최고음이 편하게 닿는 음역이에요.",
      moodFit: 0.6,
      moodFitReason: "선택한 분위기와 잘 어울려요.",
    });
    render(<FitReasons item={item} />);
    expect(screen.getByLabelText("음역 적합도 90%")).toBeInTheDocument();
    expect(
      screen.getByText("최고음이 편하게 닿는 음역이에요."),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("분위기 적합도 60%")).toBeInTheDocument();
    expect(
      screen.getByText("선택한 분위기와 잘 어울려요."),
    ).toBeInTheDocument();
  });

  it("적합도 점수가 없으면 아무것도 그리지 않는다", () => {
    const item = buildRecommendedSong({});
    const { container } = render(<FitReasons item={item} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("점수는 있으나 사유가 null 이면 배지만 노출하고 사유 문장은 생략한다", () => {
    const item = buildRecommendedSong({
      voiceFit: 0.5,
      voiceFitReason: null,
    });
    render(<FitReasons item={item} />);
    expect(screen.getByLabelText("음역 적합도 50%")).toBeInTheDocument();
    expect(screen.queryByRole("definition")).not.toBeInTheDocument();
  });
});
