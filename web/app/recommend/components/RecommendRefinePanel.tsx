"use client";

/**
 * "추천 다듬기" 접이식 패널 (recommend-page-visual-ux-audit-1708 V1·V2·V5).
 *
 * 결과 우선 노출을 위해 의도 모드 + 분위기/나이대 필터를 한 그룹으로 묶고, 기본은
 * "추천 다듬기" 1줄 컨트롤 바로 접어 둔다(탭 시 펼침). 첫 진입에서 "추천이 이미
 * 나왔다"가 즉시 보이게 하면서도 다듬기 접근성은 유지한다.
 *
 * - 컨트롤 위계 분리(V2): 의도 모드(토글)·분위기·나이대(필터)를 한 패널에 통합해
 *   "그 자리에서 바뀌는" 컨트롤임을 시각 언어로 묶는다. 이동(사회자)·뷰(리스트/스와이프)
 *   는 이 패널 밖에서 각자의 역할로 분리된다.
 * - 입력 압박 완화(V5): "아무것도 안 골라도 음역대만으로 추천됩니다" 안내 1줄.
 * - 활성 개수: 접힌 상태에서도 지금 몇 개 조건이 켜졌는지 바 라벨에 표시한다.
 */

import { useId, useState } from "react";

import type {
  AgeGroup,
  Mood,
  RecommendationPersona,
} from "@/lib/api/recommendation";

import { IntentModeToggle } from "./IntentModeToggle";
import { RecommendFilters } from "./RecommendFilters";

type RecommendRefinePanelProps = {
  selectedPersona: RecommendationPersona | null;
  onPersonaChange: (persona: RecommendationPersona | null) => void;
  selectedMood: Mood | null;
  selectedAgeGroup: AgeGroup | null;
  onMoodChange: (mood: Mood | null) => void;
  onAgeGroupChange: (ageGroup: AgeGroup | null) => void;
};

export function RecommendRefinePanel({
  selectedPersona,
  onPersonaChange,
  selectedMood,
  selectedAgeGroup,
  onMoodChange,
  onAgeGroupChange,
}: RecommendRefinePanelProps) {
  const [expanded, setExpanded] = useState(false);
  const panelId = useId();

  const activeCount =
    (selectedPersona !== null ? 1 : 0) +
    (selectedMood !== null ? 1 : 0) +
    (selectedAgeGroup !== null ? 1 : 0);

  return (
    <section aria-label="추천 다듬기" className="flex flex-col gap-3">
      <button
        type="button"
        aria-expanded={expanded}
        aria-controls={panelId}
        onClick={() => setExpanded((prev) => !prev)}
        className="flex items-center justify-between gap-2 self-stretch rounded-[var(--radius-md)] bg-[var(--badge-neutral-bg)] px-4 py-2.5 text-left text-[var(--badge-neutral-fg)] transition-colors duration-[var(--duration-base)] hover:bg-[var(--bg-subtle)] focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)] focus-visible:ring-offset-2"
      >
        <span className="flex items-center gap-2">
          <span className="text-sm font-semibold">추천 다듬기</span>
          {activeCount > 0 ? (
            <span
              data-testid="refine-active-count"
              className="inline-flex min-w-5 items-center justify-center rounded-full bg-[var(--brand-500)] px-1.5 text-xs font-semibold text-white"
            >
              {activeCount}
            </span>
          ) : (
            <span className="text-xs text-[var(--text-caption)]">
              의도·분위기·나이대 (선택)
            </span>
          )}
        </span>
        <span
          aria-hidden="true"
          className={`text-[var(--text-caption)] transition-transform duration-[var(--duration-base)] ${
            expanded ? "rotate-180" : ""
          }`}
        >
          ⌄
        </span>
      </button>

      {expanded ? (
        <div id={panelId} className="flex flex-col gap-4">
          <p className="text-xs text-[var(--text-caption)]">
            아무것도 안 골라도 음역대만으로 추천됩니다. 원하는 조건을 더하면 결과가
            다시 맞춰집니다.
          </p>
          <IntentModeToggle
            selectedPersona={selectedPersona}
            onPersonaChange={onPersonaChange}
          />
          <RecommendFilters
            selectedMood={selectedMood}
            selectedAgeGroup={selectedAgeGroup}
            onMoodChange={onMoodChange}
            onAgeGroupChange={onAgeGroupChange}
          />
        </div>
      ) : null}
    </section>
  );
}
