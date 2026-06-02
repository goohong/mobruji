/**
 * 추천 적합도 배지 + 사유 (이슈 #1484 설명가능성).
 *
 * BE 가 곡별 `voiceFit`/`moodFit`(0~1) + 한국어 사유를 내려주면, 사용자가 "왜 이 곡?"을
 * 한눈에 이해하도록 적합도 배지(라벨 + 퍼센트)와 사유 문장을 노출한다.
 *
 * - `FitBadge`: 컴팩트 칩 1개. SongCard 요약/모달 헤더 어디서나 재사용.
 * - `FitReasons`: 음역/분위기 적합도 배지 + 사유를 dl/dt/dd 로 묶은 블록. 카드 펼침 패널과
 *   상세 모달이 공유 — 둘 다 같은 표현을 쓰도록 단일 컴포넌트로 둔다.
 *
 * 톤/퍼센트 계산은 `@/lib/recommendationFit` 가 SoT. 값이 모두 없으면 `null` 을 반환해
 * 호출 측이 빈 블록을 그리지 않게 한다(과거 추천 재조회 경로 안전).
 */

"use client";

import type { RecommendedSongResponse } from "@/lib/api/recommendation";
import {
  difficultyLabel,
  difficultyTone,
  type Difficulty,
} from "@/lib/difficulty";
import { toFitDisplay } from "@/lib/recommendationFit";

type FitBadgeProps = {
  label: string;
  fit: number;
};

export function FitBadge({ label, fit }: FitBadgeProps) {
  const { percent, toneClass } = toFitDisplay(fit);
  return (
    <span
      aria-label={`${label} ${percent}%`}
      className={`inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-semibold ${toneClass}`}
    >
      <span aria-hidden="true">{label}</span>
      <span aria-hidden="true" className="font-mono tabular-nums">
        {percent}%
      </span>
    </span>
  );
}

type FitRow = {
  key: "voice" | "mood";
  label: string;
  fit: number;
  reason: string | null;
};

/**
 * 응답에서 노출 가능한 적합도 행만 추려 반환. 점수가 숫자일 때만 포함한다 —
 * `null`/생략(과거 추천)인 신호는 건너뛴다.
 */
export function collectFitRows(item: RecommendedSongResponse): FitRow[] {
  const rows: FitRow[] = [];
  if (typeof item.voiceFit === "number") {
    rows.push({
      key: "voice",
      label: "음역 적합도",
      fit: item.voiceFit,
      reason: item.voiceFitReason ?? null,
    });
  }
  if (typeof item.moodFit === "number") {
    rows.push({
      key: "mood",
      label: "분위기 적합도",
      fit: item.moodFit,
      reason: item.moodFitReason ?? null,
    });
  }
  return rows;
}

/**
 * 연습 난이도 배지 (#1550, BE #1494 practiceDifficulty).
 *
 * 적합도 배지(`FitBadge`)와 같은 칩 형태를 따르되, 0~1 점수가 아니라 난이도 enum 을
 * 라벨/톤으로 매핑한다(`@/lib/difficulty`). 난이도가 `null`(음역 미보유 곡)이면 "정보 없음"
 * 으로 graceful 하게 노출하고 중립 톤을 쓴다 — BE 사유 문장도 같은 사실을 알려 준다.
 */
type PracticeDifficultyBadgeProps = {
  difficulty: Difficulty | null;
};

const NEUTRAL_TONE = "bg-[var(--badge-neutral-bg)] text-[var(--badge-neutral-fg)]";

export function PracticeDifficultyBadge({
  difficulty,
}: PracticeDifficultyBadgeProps) {
  const valueLabel = difficulty ? difficultyLabel(difficulty) : "정보 없음";
  const toneClass = difficulty ? difficultyTone(difficulty) : NEUTRAL_TONE;
  return (
    <span
      aria-label={`연습 난이도 ${valueLabel}`}
      className={`inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-semibold ${toneClass}`}
    >
      <span aria-hidden="true">연습 난이도</span>
      <span aria-hidden="true">{valueLabel}</span>
    </span>
  );
}

type FitReasonsProps = {
  item: RecommendedSongResponse;
};

export function FitReasons({ item }: FitReasonsProps) {
  const rows = collectFitRows(item);
  const practiceDifficulty = item.practiceDifficulty ?? null;
  const practiceDifficultyReason = item.practiceDifficultyReason ?? null;
  // 연습 난이도 행은 BE 가 난이도 또는 사유 중 하나라도 내려줬을 때 노출. 둘 다 없는
  // (필드 미반영 과거 추천) 경로에서는 종전대로 적합도 행만 그린다.
  const hasPracticeDifficulty =
    practiceDifficulty !== null || practiceDifficultyReason !== null;
  if (rows.length === 0 && !hasPracticeDifficulty) {
    return null;
  }
  return (
    <dl aria-label="적합도 사유" className="flex flex-col gap-2">
      {rows.map((row) => (
        <div key={row.key} className="flex flex-col gap-1">
          <dt>
            <FitBadge label={row.label} fit={row.fit} />
          </dt>
          {row.reason ? (
            <dd className="text-xs text-[var(--text-secondary)]">
              {row.reason}
            </dd>
          ) : null}
        </div>
      ))}
      {hasPracticeDifficulty ? (
        <div key="practice-difficulty" className="flex flex-col gap-1">
          <dt>
            <PracticeDifficultyBadge difficulty={practiceDifficulty} />
          </dt>
          {practiceDifficultyReason ? (
            <dd className="text-xs text-[var(--text-secondary)]">
              {practiceDifficultyReason}
            </dd>
          ) : null}
        </div>
      ) : null}
    </dl>
  );
}
