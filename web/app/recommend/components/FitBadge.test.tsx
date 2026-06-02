/**
 * FitBadge / FitReasons 컴포넌트 테스트 (이슈 #1484, #1550).
 *
 * 검증 포인트:
 *   1) FitBadge: 라벨 + 퍼센트 aria-label 노출.
 *   2) FitReasons: voiceFit/moodFit 둘 다 있으면 배지 + 한국어 사유 노출.
 *   3) FitReasons: 적합도 점수가 없는(과거 추천) 경우 아무것도 그리지 않는다(null).
 *   4) FitReasons: 점수는 있으나 사유가 null 이면 배지만, 사유 문장은 생략.
 *   5) PracticeDifficultyBadge: 난이도 라벨/“정보 없음” aria-label 노출 (#1550).
 *   6) FitReasons: practiceDifficulty + 사유를 연습 난이도 배지·사유 행으로 노출 (#1550).
 */

import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

import { buildRecommendedSong } from "@/lib/test-fixtures/history";

import { FitBadge, FitReasons, PracticeDifficultyBadge } from "./FitBadge";

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

  it("practiceDifficulty + 사유를 연습 난이도 배지·사유 행으로 노출한다", () => {
    const item = buildRecommendedSong({
      practiceDifficulty: "HARD",
      practiceDifficultyReason: "최고음 라5, 고음·넓은 음역이라 도전적인 곡이에요",
    });
    render(<FitReasons item={item} />);
    expect(screen.getByLabelText("연습 난이도 Hard")).toBeInTheDocument();
    expect(
      screen.getByText("최고음 라5, 고음·넓은 음역이라 도전적인 곡이에요"),
    ).toBeInTheDocument();
  });

  it("난이도가 null 이어도 사유가 있으면 정보 없음 배지 + 사유를 노출한다", () => {
    const item = buildRecommendedSong({
      practiceDifficulty: null,
      practiceDifficultyReason: "아직 음역대 분석 정보가 없어 난이도를 가늠하기 어려워요",
    });
    render(<FitReasons item={item} />);
    expect(screen.getByLabelText("연습 난이도 정보 없음")).toBeInTheDocument();
    expect(
      screen.getByText("아직 음역대 분석 정보가 없어 난이도를 가늠하기 어려워요"),
    ).toBeInTheDocument();
  });

  it("적합도·연습 난이도 둘 다 없으면 아무것도 그리지 않는다", () => {
    const item = buildRecommendedSong({});
    const { container } = render(<FitReasons item={item} />);
    expect(container).toBeEmptyDOMElement();
  });
});

describe("PracticeDifficultyBadge", () => {
  it("난이도 라벨을 aria-label 로 노출한다", () => {
    render(<PracticeDifficultyBadge difficulty="NORMAL" />);
    expect(screen.getByLabelText("연습 난이도 Normal")).toBeInTheDocument();
  });

  it("난이도가 null 이면 정보 없음으로 노출한다", () => {
    render(<PracticeDifficultyBadge difficulty={null} />);
    expect(screen.getByLabelText("연습 난이도 정보 없음")).toBeInTheDocument();
  });
});
