"use client";

/**
 * P-D 모임 사회자 모드의 좌중 연령대 분포 입력 (이슈 #1601, spec §2 P-D 입력 신호).
 *
 * 단일 추천(P-C)의 나이대 칩은 "나 한 명"의 세대를 고르는 단일 선택이지만, P-D 는
 * **여러 명이 섞인 자리**라 연령대를 다중 선택한다 — 좌중 구성을 시퀀스 가중(generationFit
 * 분포)에 반영한다. 미선택이면 연령대 신호 없이 동작(하위호환).
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
  selected: AgeGroup[];
  onChange: (next: AgeGroup[]) => void;
};

export function CrowdAgeGroupPicker({
  selected,
  onChange,
}: CrowdAgeGroupPickerProps) {
  const toggle = (value: AgeGroup) => {
    if (selected.includes(value)) {
      onChange(selected.filter((item) => item !== value));
    } else {
      onChange([...selected, value]);
    }
  };

  return (
    <div role="group" aria-label="인원 연령대" className="flex flex-col gap-2">
      <div className="flex items-baseline gap-2">
        <span className="text-sm font-medium text-[var(--text-primary)]">
          인원 연령대
        </span>
        <span className="text-xs text-[var(--text-caption)]">
          자리에 있는 연령대를 모두 골라보세요 (선택)
        </span>
      </div>
      <div className="flex flex-wrap gap-2">
        {AGE_GROUP_OPTIONS.map(({ value, label }) => {
          const active = selected.includes(value);
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
