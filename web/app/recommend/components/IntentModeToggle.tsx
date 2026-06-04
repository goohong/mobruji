"use client";

/**
 * 추천 의도 모드 토글 (persona-expansion-social-emotional.md §5-6, 이슈 #1600 / #1844 / #1848).
 *
 * 기존 추천 화면(음역대 + 분위기/나이대)에 "감정 축" 의도 모드 진입을 더한다 — "안 망할 곡"
 * (P-E 안전곡), 그 반대축 "고음 질러 박수받기"(P-F 과시·킬링파트), 그리고 "둘이 함께 부를 곡"
 * (P-G 듀엣, 로드맵 사회 축 마지막). spec §8 Q2(온보딩 `PersonaEntryPath` 통합 vs 추천 화면 내
 * 모드 선택)는 미결정 상태라, 이슈 요구사항("기존 추천 화면에 의도 모드 선택 진입")에 맞춰
 * **추천 화면 내 토글**(Q2 선택지 b)을 1차 가설로 둔다.
 *
 * 동작:
 *  - 모드들은 **상호 배타**다. 한 모드를 켜면 다른 모드는 자동으로 꺼진다(persona 단일 값).
 *  - 켜진 버튼을 다시 누르면 `null` → 현행 default 추천(하위호환, 모드 미선택 = 기존 화면 불변).
 *  - 각 버튼 `aria-pressed` — "켜짐/꺼짐" 시맨틱이 명확. 스타일은 RecommendFilters 칩 + badge 토큰과 정합.
 *  - P-G 듀엣은 파트너 음역·성별 추가 입력이 필요해, 토글로 켜면 결과 피드 측(DuetRecommendationFeed)이
 *    파트너 입력 패널을 띄운다. P-D 시퀀스는 별도 화면(RecommendModeGroup 의 이동형 진입).
 */

import type { RecommendationPersona } from "@/lib/api/recommendation";
import {
  DUET_SONG_PERSONA,
  SAFE_SONG_PERSONA,
  SHOWOFF_SONG_PERSONA,
} from "@/lib/persona";

type IntentModeToggleProps = {
  selectedPersona: RecommendationPersona | null;
  onPersonaChange: (persona: RecommendationPersona | null) => void;
};

type IntentMode = {
  persona: RecommendationPersona;
  title: string;
  caption: string;
};

const INTENT_MODES: IntentMode[] = [
  {
    persona: SAFE_SONG_PERSONA,
    title: "안 망할 곡 추천받기",
    caption: "좁은 음역·쉬운 난이도·누구나 아는 곡으로 안전하게",
  },
  {
    persona: SHOWOFF_SONG_PERSONA,
    title: "고음 질러 박수받기",
    caption: "내 음역 천장 근처 킬링파트가 있는 곡으로 한 방",
  },
  {
    persona: DUET_SONG_PERSONA,
    title: "둘이 함께 부를 곡 추천받기",
    caption: "두 사람 음역에 맞춰 파트를 나눠 부르기 좋은 듀엣곡으로",
  },
];

export function IntentModeToggle({
  selectedPersona,
  onPersonaChange,
}: IntentModeToggleProps) {
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
      <div className="flex flex-col gap-2 sm:flex-row">
        {INTENT_MODES.map(({ persona, title, caption }) => {
          const active = selectedPersona === persona;
          return (
            <button
              key={persona}
              type="button"
              aria-pressed={active}
              // 상호 배타: 켜져 있으면 끄고(null), 아니면 이 페르소나로 전환한다.
              onClick={() => onPersonaChange(active ? null : persona)}
              className={`flex flex-1 flex-col items-start gap-0.5 rounded-[var(--radius-md)] px-4 py-2.5 text-left transition-colors duration-[var(--duration-base)] focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)] focus-visible:ring-offset-2 ${
                active
                  ? "bg-[var(--brand-500)] text-white hover:bg-[var(--brand-600)]"
                  : "bg-[var(--badge-neutral-bg)] text-[var(--badge-neutral-fg)] hover:bg-[var(--bg-subtle)]"
              }`}
            >
              <span className="text-sm font-semibold">{title}</span>
              <span
                className={`text-xs ${
                  active ? "text-white/80" : "text-[var(--text-caption)]"
                }`}
              >
                {caption}
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
