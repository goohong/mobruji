"use client";

/**
 * 추천 요청 즉석 페르소나(P-C) 입력 UI (directive roadmap-mood-age-ui).
 *
 * BE 는 mood(6종)·ageGroup(6종) 입력을 받지만 web /recommend 화면엔 입력 UI 가
 * 없어 음역대만으로 추천을 받았다. 여기에 분위기 칩 + 나이대 칩을 추가해
 * "지금 이 순간의 기분/세대"를 한 번 탭으로 추천에 반영한다(영속 페르소나 X — 즉석).
 *
 * 동작:
 *  - 단일 선택. 선택된 칩을 다시 누르면 해제(null) → 해당 신호 가중 없음(하위호환).
 *  - 칩은 toggle 버튼(`aria-pressed`)이라 "선택 해제 가능" 시맨틱이 라디오보다 정확하다.
 *  - 스타일/토큰은 기존 ViewModeToggle 세그먼트 + badge 토큰을 재사용한다.
 */

import { AgeGroup, Mood } from "@/lib/api/recommendation";

/** BE Mood enum → 한국어 라벨 (history 페이지 MOOD_LABELS 와 동일 톤). */
const MOOD_OPTIONS: ReadonlyArray<{ value: Mood; label: string }> = [
  { value: "UPBEAT", label: "신나는" },
  { value: "CALM", label: "잔잔한" },
  { value: "EMOTIONAL", label: "감성적인" },
  { value: "POWERFUL", label: "파워풀한" },
  { value: "GROOVY", label: "그루비한" },
  { value: "NOSTALGIC", label: "추억의" },
];

/** BE AgeGroup enum → 한국어 라벨. */
const AGE_GROUP_OPTIONS: ReadonlyArray<{ value: AgeGroup; label: string }> = [
  { value: "TEENS", label: "10대" },
  { value: "TWENTIES", label: "20대" },
  { value: "THIRTIES", label: "30대" },
  { value: "FORTIES", label: "40대" },
  { value: "FIFTIES", label: "50대" },
  { value: "SIXTIES_PLUS", label: "60대+" },
];

type RecommendFiltersProps = {
  selectedMood: Mood | null;
  selectedAgeGroup: AgeGroup | null;
  onMoodChange: (mood: Mood | null) => void;
  onAgeGroupChange: (ageGroup: AgeGroup | null) => void;
};

export function RecommendFilters({
  selectedMood,
  selectedAgeGroup,
  onMoodChange,
  onAgeGroupChange,
}: RecommendFiltersProps) {
  return (
    <div className="flex flex-col gap-4">
      <FilterChipGroup
        label="분위기"
        helpText="원하는 분위기를 골라보세요 (선택)"
      >
        {MOOD_OPTIONS.map(({ value, label }) => (
          <FilterChip
            key={value}
            label={label}
            active={selectedMood === value}
            onToggle={() =>
              onMoodChange(selectedMood === value ? null : value)
            }
          />
        ))}
      </FilterChipGroup>

      <FilterChipGroup label="나이대" helpText="세대별 인기곡에 가중 (선택)">
        {AGE_GROUP_OPTIONS.map(({ value, label }) => (
          <FilterChip
            key={value}
            label={label}
            active={selectedAgeGroup === value}
            onToggle={() =>
              onAgeGroupChange(selectedAgeGroup === value ? null : value)
            }
          />
        ))}
      </FilterChipGroup>
    </div>
  );
}

type FilterChipGroupProps = {
  label: string;
  helpText: string;
  children: React.ReactNode;
};

function FilterChipGroup({ label, helpText, children }: FilterChipGroupProps) {
  return (
    <div role="group" aria-label={label} className="flex flex-col gap-2">
      <div className="flex items-baseline gap-2">
        <span className="text-sm font-medium text-[var(--text-primary)]">
          {label}
        </span>
        <span className="text-xs text-[var(--text-caption)]">{helpText}</span>
      </div>
      <div className="flex flex-wrap gap-2">{children}</div>
    </div>
  );
}

type FilterChipProps = {
  label: string;
  active: boolean;
  onToggle: () => void;
};

function FilterChip({ label, active, onToggle }: FilterChipProps) {
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onToggle}
      className={`min-h-9 rounded-full px-4 text-sm font-medium transition-colors duration-[var(--duration-base)] focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)] focus-visible:ring-offset-2 ${
        active
          ? "bg-[var(--brand-500)] text-white hover:bg-[var(--brand-600)]"
          : "bg-[var(--badge-neutral-bg)] text-[var(--badge-neutral-fg)] hover:bg-[var(--bg-subtle)]"
      }`}
    >
      {label}
    </button>
  );
}
