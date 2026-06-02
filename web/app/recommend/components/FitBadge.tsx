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

type FitReasonsProps = {
  item: RecommendedSongResponse;
};

export function FitReasons({ item }: FitReasonsProps) {
  const rows = collectFitRows(item);
  if (rows.length === 0) {
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
    </dl>
  );
}
