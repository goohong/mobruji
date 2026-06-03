/**
 * FitBadge / FitReasons 컴포넌트 테스트 (이슈 #1484, #1550, #1544).
 *
 * 검증 포인트:
 *   1) FitBadge: 라벨 + 퍼센트 aria-label 노출.
 *   2) FitReasons: voiceFit/moodFit 둘 다 있으면 배지 + 한국어 사유 노출.
 *   3) FitReasons: 적합도 점수가 없는(과거 추천) 경우 아무것도 그리지 않는다(null).
 *   4) FitReasons: 점수는 있으나 사유가 null 이면 배지만, 사유 문장은 생략.
 *   5) PracticeDifficultyBadge: 난이도 라벨/“정보 없음” aria-label 노출 (#1550).
 *   6) FitReasons: practiceDifficulty + 사유를 연습 난이도 배지·사유 행으로 노출 (#1550).
 *   7) FitReasons: suggestedTranspose + 사유 + before→after voiceFit 비교 노출 (#1544).
 *   8) formatTransposeLabel: 양/음/0 반음 표기 규칙 (#1544).
 */

import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

import { buildRecommendedSong } from "@/lib/test-fixtures/history";

import {
  FitBadge,
  FitReasons,
  PracticeDifficultyBadge,
  formatTransposeLabel,
} from "./FitBadge";

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

  it("적합도·연습 난이도·조옮김 권장 모두 없으면 아무것도 그리지 않는다", () => {
    const item = buildRecommendedSong({});
    const { container } = render(<FitReasons item={item} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("suggestedTranspose + 사유 + before→after voiceFit 비교를 노출한다", () => {
    const item = buildRecommendedSong({
      voiceFit: 0.3,
      suggestedTranspose: -6,
      transposedVoiceFit: 0.8,
      suggestedTransposeReason: "6키 내려 부르면 음역대에 더 잘 맞아요",
    });
    render(<FitReasons item={item} />);
    expect(screen.getByLabelText("조옮김 -6키")).toBeInTheDocument();
    expect(
      screen.getByText("6키 내려 부르면 음역대에 더 잘 맞아요"),
    ).toBeInTheDocument();
    // 현재 30% → 조옮김 후 80% 비교가 aria-label 로 노출된다.
    expect(
      screen.getByLabelText("조옮김 시 음역 적합도 30%에서 80%로 상승"),
    ).toBeInTheDocument();
  });

  it("transposedVoiceFit 가 없으면 비교는 생략하고 사유만 노출한다", () => {
    const item = buildRecommendedSong({
      suggestedTranspose: 2,
      suggestedTransposeReason: "2키 올려 부르면 더 편해요",
    });
    render(<FitReasons item={item} />);
    expect(screen.getByLabelText("조옮김 +2키")).toBeInTheDocument();
    expect(screen.getByText("2키 올려 부르면 더 편해요")).toBeInTheDocument();
    expect(
      screen.queryByLabelText(/조옮김 시 음역 적합도/),
    ).not.toBeInTheDocument();
  });

  it("반음 수 없이 사유만 있어도 권장 행을 노출한다", () => {
    const item = buildRecommendedSong({
      suggestedTransposeReason: "조옮김하면 더 편하게 부를 수 있어요",
    });
    render(<FitReasons item={item} />);
    expect(screen.getByLabelText("조옮김 권장")).toBeInTheDocument();
    expect(
      screen.getByText("조옮김하면 더 편하게 부를 수 있어요"),
    ).toBeInTheDocument();
  });

  it("조옮김 필드가 모두 null 이면 권장 행을 그리지 않는다", () => {
    const item = buildRecommendedSong({
      voiceFit: 0.9,
      voiceFitReason: "원조로도 잘 맞아요",
      suggestedTranspose: null,
      suggestedTransposeReason: null,
    });
    render(<FitReasons item={item} />);
    expect(screen.queryByText(/조옮김/)).not.toBeInTheDocument();
  });
});

describe("formatTransposeLabel", () => {
  it("양수는 +N키(올림)로 표기한다", () => {
    expect(formatTransposeLabel(3)).toBe("+3키");
  });

  it("음수는 부호 그대로 N키(내림)로 표기한다", () => {
    expect(formatTransposeLabel(-6)).toBe("-6키");
  });

  it("0 은 원조로 표기한다", () => {
    expect(formatTransposeLabel(0)).toBe("원조");
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
