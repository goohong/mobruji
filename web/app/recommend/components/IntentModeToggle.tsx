"use client";

/**
 * 추천 의도 모드 토글 (persona-expansion-social-emotional.md §5-6, 이슈 #1600).
 *
 * 기존 추천 화면(음역대 + 분위기/나이대)에 "안 망할 곡"(P-E 안전곡) 의도 모드 진입을
 * 더한다. spec §8 Q2(온보딩 `PersonaEntryPath` 통합 vs 추천 화면 내 모드 선택)는 미결정
 * 상태라, 이슈 요구사항("기존 추천 화면에 의도 모드 선택 진입")에 맞춰 **추천 화면 내
 * 토글**(Q2 선택지 b)을 1차 가설로 둔다.
 *
 * 동작:
 *  - 단일 토글. 켜면 `persona=SAFE_SONG_PERSONA`(P-E), 끄면 `null` → 현행 default 추천
 *    (하위호환, 모드 미선택 = 기존 화면 동작 불변).
 *  - `aria-pressed` 토글 버튼 — "켜짐/꺼짐" 시맨틱이 명확. 스타일은 RecommendFilters
 *    칩 + badge 토큰과 정합.
 *  - 1차는 P-E 만 노출. P-D/P-F/P-G 의도 모드는 후속 이슈에서 같은 패턴으로 확장.
 */

import type { RecommendationPersona } from "@/lib/api/recommendation";
import { SAFE_SONG_PERSONA } from "@/lib/persona";

type IntentModeToggleProps = {
  selectedPersona: RecommendationPersona | null;
  onPersonaChange: (persona: RecommendationPersona | null) => void;
};

export function IntentModeToggle({
  selectedPersona,
  onPersonaChange,
}: IntentModeToggleProps) {
  const safeMode = selectedPersona === SAFE_SONG_PERSONA;

  return (
    <div role="group" aria-label="추천 의도 모드" className="flex flex-col gap-2">
      <div className="flex items-baseline gap-2">
        <span className="text-sm font-medium text-[var(--text-primary)]">
          의도 모드
        </span>
        <span className="text-xs text-[var(--text-caption)]">
          오늘은 어떻게 부르고 싶나요? (선택)
        </span>
      </div>
      <button
        type="button"
        aria-pressed={safeMode}
        onClick={() =>
          onPersonaChange(safeMode ? null : SAFE_SONG_PERSONA)
        }
        className={`flex flex-col items-start gap-0.5 self-start rounded-[var(--radius-md)] px-4 py-2.5 text-left transition-colors duration-[var(--duration-base)] focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)] focus-visible:ring-offset-2 ${
          safeMode
            ? "bg-[var(--brand-500)] text-white hover:bg-[var(--brand-600)]"
            : "bg-[var(--badge-neutral-bg)] text-[var(--badge-neutral-fg)] hover:bg-[var(--bg-subtle)]"
        }`}
      >
        <span className="text-sm font-semibold">안 망할 곡 추천받기</span>
        <span
          className={`text-xs ${
            safeMode ? "text-white/80" : "text-[var(--text-caption)]"
          }`}
        >
          좁은 음역·쉬운 난이도·누구나 아는 곡으로 안전하게
        </span>
      </button>
    </div>
  );
}
