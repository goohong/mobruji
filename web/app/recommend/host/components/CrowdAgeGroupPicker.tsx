"use client";

/**
 * P-D 모임 사회자 모드의 좌중 대표 연령대 입력 (이슈 #1601, spec §2 P-D 입력 신호).
 *
 * be #1837 시퀀스 엔드포인트는 좌중 연령대를 **단일 대표값**(`ageGroup`)으로 받아
 * generationFit 분포 가중에 반영한다. 따라서 자리의 대표 연령대를 하나 고르는 단일 선택이며,
 * 같은 칩을 다시 누르면 해제된다. 미선택이면 연령대 신호 없이 동작(하위호환).
 */

import type { AgeGroup } from "@/lib/api/recommendation";

/** BE AgeGroup enum → 한국어 라벨 (RecommendFilters 와 동일 톤). */
const AGE_GROUP_OPTIONS: ReadonlyArray<{ value: AgeGroup; label: string }> = [
  { value: "TEENS", label: "10대" },
  { value: "TWENTIES", label: "20대" },
  { value: "THIRTIES", label: "30대" },
  { value: "FORTIES", label: "40대" },
  { value: "FIFTIES", label: "50대" },
  { value: "SIXTIES_PLUS", label: "60대+" },
];

type CrowdAgeGroupPickerProps = {
  selected: AgeGroup | null;
  onChange: (next: AgeGroup | null) => void;
};

export function CrowdAgeGroupPicker({
  selected,
  onChange,
}: CrowdAgeGroupPickerProps) {
  const toggle = (value: AgeGroup) => {
    onChange(selected === value ? null : value);
  };

  return (
    <div role="group" aria-label="인원 연령대" className="flex flex-col gap-2">
      <div className="flex items-baseline gap-2">
        <span className="text-sm font-medium text-[var(--text-primary)]">
          인원 연령대
        </span>
        <span className="text-xs text-[var(--text-caption)]">
          자리의 대표 연령대를 골라보세요 (선택)
        </span>
      </div>
      <div className="flex flex-wrap gap-2">
        {AGE_GROUP_OPTIONS.map(({ value, label }) => {
          const active = selected === value;
          return (
            <button
              key={value}
              type="button"
              aria-pressed={active}
              onClick={() => toggle(value)}
              className={`min-h-9 rounded-full px-4 text-sm font-medium transition-colors duration-[var(--duration-base)] focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)] focus-visible:ring-offset-2 ${
                active
                  ? "bg-[var(--brand-500)] text-white hover:bg-[var(--brand-600)]"
                  : "bg-[var(--badge-neutral-bg)] text-[var(--badge-neutral-fg)] hover:bg-[var(--bg-subtle)]"
              }`}
            >
              {label}
            </button>
          );
        })}
      </div>
    </div>
  );
}
