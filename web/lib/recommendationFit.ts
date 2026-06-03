/**
 * 추천 적합도(fit) 표시 헬퍼 (이슈 #1484 설명가능성).
 *
 * BE PR #1502 가 추천 응답에 `voiceFit`/`moodFit`(0~1 raw 점수)을 노출함에 따라,
 * 이 0~1 점수를 카드/모달 배지로 그릴 때 필요한 표시값(퍼센트 + 톤 클래스 + 레벨)을
 * 한 곳에서 계산한다. SongCard 와 SongDetailContent 가 같은 톤/임계값을 공유하도록
 * 컴포넌트가 아니라 순수 함수로 둔다.
 *
 * 톤 임계값은 점수의 직관과 일치 — 높을수록 "잘 맞음"(success/emerald),
 * 중간은 주의(warning/amber), 낮으면 중립(neutral) 으로 과도한 부정 강조를 피한다.
 * 색은 tokens.css 의 `--badge-*` 페어를 그대로 재사용한다(라이트/다크 자동 대응).
 */

export type FitLevel = "high" | "mid" | "low";

export type FitDisplay = {
  /** 0~100 정수 퍼센트. 배지/aria 에 그대로 노출. */
  percent: number;
  level: FitLevel;
  /** tokens.css `--badge-*` 페어 기반 Tailwind 클래스 (bg + text). */
  toneClass: string;
};

const HIGH_THRESHOLD = 0.7;
const MID_THRESHOLD = 0.4;

/**
 * 0~1 적합도 점수를 표시값으로 변환. 범위를 벗어난 입력은 [0, 1] 로 clamp 한다.
 */
export function toFitDisplay(fit: number): FitDisplay {
  const clamped = Math.max(0, Math.min(1, fit));
  const percent = Math.round(clamped * 100);
  const level = resolveLevel(clamped);
  return { percent, level, toneClass: toneClassFor(level) };
}

function resolveLevel(fit: number): FitLevel {
  if (fit >= HIGH_THRESHOLD) {
    return "high";
  }
  if (fit >= MID_THRESHOLD) {
    return "mid";
  }
  return "low";
}

function toneClassFor(level: FitLevel): string {
  switch (level) {
    case "high":
      return "bg-[var(--badge-success-bg)] text-[var(--badge-success-fg)]";
    case "mid":
      return "bg-[var(--badge-warning-bg)] text-[var(--badge-warning-fg)]";
    case "low":
      return "bg-[var(--badge-neutral-bg)] text-[var(--badge-neutral-fg)]";
  }
}
