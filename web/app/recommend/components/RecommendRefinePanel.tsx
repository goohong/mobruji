"use client";

/**
 * "추천 다듬기" 접이식 필터 패널 (recommend-page-visual-ux-audit-1708 V1·V2·V5,
 * 선택형 진입 그룹화 #1712).
 *
 * 결과 우선 노출을 위해 분위기/나이대 **필터** 를 한 그룹으로 묶고, 기본은 "추천 다듬기"
 * 1줄 컨트롤 바로 접어 둔다(탭 시 펼침). 첫 진입에서 "추천이 이미 나왔다"가 즉시 보이게
 * 하면서도 다듬기 접근성은 유지한다.
 *
 * - 성격별 그룹화(#1712): 이 패널은 "지금 결과를 그 자리에서 좁히는 **필터**"만 담는다.
 *   "다른 추천 방식으로 전환하는 **모드 진입**"(의도 모드 토글·호스트 모드 이동)은
 *   결과 아래 RecommendModeGroup 으로 분리해 "필터 조정" vs "다른 방식으로 추천받기"를
 *   사용자가 별개로 인지하게 한다.
 * - 입력 압박 완화(V5): "아무것도 안 골라도 음역대만으로 추천됩니다" 안내 1줄.
 * - 활성 개수: 접힌 상태에서도 지금 몇 개 필터가 켜졌는지 바 라벨에 표시한다.
 */

import { useId, useState } from "react";

import type { AgeGroup, Mood } from "@/lib/api/recommendation";

import { RecommendFilters } from "./RecommendFilters";

type RecommendRefinePanelProps = {
  selectedMood: Mood | null;
  selectedAgeGroup: AgeGroup | null;
  onMoodChange: (mood: Mood | null) => void;
  onAgeGroupChange: (ageGroup: AgeGroup | null) => void;
  /** 선택된 분위기·나이대 필터를 한 번에 비운다(이슈 #1715 "모두 해제"). */
  onClearAll: () => void;
};

export function RecommendRefinePanel({
  selectedMood,
  selectedAgeGroup,
  onMoodChange,
  onAgeGroupChange,
  onClearAll,
}: RecommendRefinePanelProps) {
  const [expanded, setExpanded] = useState(false);
  const panelId = useId();

  const activeCount =
    (selectedMood !== null ? 1 : 0) + (selectedAgeGroup !== null ? 1 : 0);

  return (
    <section aria-label="추천 필터" className="flex flex-col gap-3">
      {/* closes #1715 — 토글 바와 "모두 해제"를 형제로 둔다(버튼 중첩 = HTML 위반 회피).
          모두 해제는 필터가 1개 이상 켜졌을 때만 노출해 접힌 상태에서도 바로 비울 수 있다. */}
      <div className="flex items-center gap-2">
        <button
          type="button"
          aria-expanded={expanded}
          aria-controls={panelId}
          onClick={() => setExpanded((prev) => !prev)}
          className="flex flex-1 items-center justify-between gap-2 rounded-[var(--radius-md)] bg-[var(--badge-neutral-bg)] px-4 py-2.5 text-left text-[var(--badge-neutral-fg)] transition-colors duration-[var(--duration-base)] hover:bg-[var(--bg-subtle)] focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)] focus-visible:ring-offset-2"
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
                분위기·나이대 (선택)
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
        {activeCount > 0 ? (
          <button
            type="button"
            onClick={onClearAll}
            data-testid="refine-clear-all"
            className="shrink-0 rounded-[var(--radius-md)] px-3 py-2.5 text-xs font-medium text-[var(--text-secondary)] transition-colors duration-[var(--duration-base)] hover:bg-[var(--bg-subtle)] hover:text-[var(--text-primary)] focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)] focus-visible:ring-offset-2"
          >
            모두 해제
          </button>
        ) : null}
      </div>

      {expanded ? (
        <div id={panelId} className="flex flex-col gap-4">
          <p className="text-xs text-[var(--text-caption)]">
            아무것도 안 골라도 음역대만으로 추천됩니다. 원하는 조건을 더하면 결과가
            다시 맞춰집니다.
          </p>
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
