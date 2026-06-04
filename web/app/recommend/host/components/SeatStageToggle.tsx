"use client";

/**
 * 자리 단계 토글 (이슈 #1601, spec §2 P-D / §5-6).
 *
 * 모임 사회자 모드의 시퀀스(워밍업 → 고조 → 마무리) 중 현재 보고 있는 단계를 고르는
 * 세그먼트 토글이다. 사용자는 진행 버튼으로 순서대로 흐를 수도, 이 토글로 특정 단계로
 * 바로 점프할 수도 있다. 각 단계 버튼은 곡 수를 함께 보여 어느 단계에 곡이 있는지 한눈에
 * 보이게 한다. `role="radiogroup"` + `aria-checked` 로 현재 단계를 스크린 리더에 노출한다.
 */

import {
  SEQUENCE_STAGE_ORDER,
  stageMeta,
} from "@/lib/sequence";
import type {
  SequenceStage,
  SequenceStageBundle,
} from "@/lib/api/recommendation";

type SeatStageToggleProps = {
  stages: SequenceStageBundle[];
  currentStage: SequenceStage;
  onSelect: (stage: SequenceStage) => void;
};

export function SeatStageToggle({
  stages,
  currentStage,
  onSelect,
}: SeatStageToggleProps) {
  const songCountByStage = new Map<SequenceStage, number>(
    stages.map((bundle) => [bundle.stage, bundle.recommendations.length]),
  );

  return (
    <div
      role="radiogroup"
      aria-label="자리 단계"
      className="flex items-center gap-1 self-start rounded-full bg-[var(--bg-subtle)] p-1"
    >
      {SEQUENCE_STAGE_ORDER.map((stage) => {
        const active = stage === currentStage;
        const { label } = stageMeta(stage);
        const count = songCountByStage.get(stage) ?? 0;
        return (
          <button
            key={stage}
            type="button"
            role="radio"
            aria-checked={active}
            onClick={() => onSelect(stage)}
            className={`min-h-9 rounded-full px-4 text-sm font-medium transition-colors duration-[var(--duration-base)] focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)] ${
              active
                ? "bg-[var(--bg-base)] text-[var(--text-primary)] shadow-[var(--shadow-sm)]"
                : "text-[var(--text-secondary)] hover:text-[var(--text-primary)]"
            }`}
          >
            {label}
            <span className="ml-1 text-xs text-[var(--text-caption)]">
              {count}
            </span>
          </button>
        );
      })}
    </div>
  );
}
