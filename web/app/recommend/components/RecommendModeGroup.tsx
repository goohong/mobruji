"use client";

/**
 * "다른 방식으로 추천받기" 모드 진입 그룹 (선택형 진입 그룹화 #1712).
 *
 * 성격이 다른 세 진입(필터·의도 모드·호스트 모드)이 같은 비주얼 무게로 섞여 있어
 * 사용자가 "이동 vs 모드 vs 필터"를 구분하지 못하던 문제(#1708 스크린샷 분석)를 풀기 위해,
 * **추천 방식을 통째로 바꾸는 모드 진입**(의도 모드 토글 + 호스트 모드 이동)을 한 섹션으로
 * 묶고 짧은 구분 제목을 둔다. "지금 결과를 좁히는 필터"(RecommendRefinePanel)와 별개로
 * 인지하게 한다.
 *
 * - 의도 모드: 같은 화면에서 토글 → persona 를 더해 결과를 재발화(현 화면 유지).
 * - 호스트 모드: /recommend/host 로 **페이지 이동**. 이동을 암시하는 시각 단서로
 *   "새 화면" 라벨 + 화살표(›) 어포던스를 함께 둬, 토글과 성격이 다름을 드러낸다.
 *
 * 결과 우선(#1711) 유지를 위해 결과 피드 아래에 2차 액션으로 배치한다.
 */

import Link from "next/link";

import type { RecommendationPersona } from "@/lib/api/recommendation";

import { IntentModeToggle } from "./IntentModeToggle";

type RecommendModeGroupProps = {
  selectedPersona: RecommendationPersona | null;
  onPersonaChange: (persona: RecommendationPersona | null) => void;
};

export function RecommendModeGroup({
  selectedPersona,
  onPersonaChange,
}: RecommendModeGroupProps) {
  return (
    <section
      aria-label="다른 방식으로 추천받기"
      className="flex flex-col gap-3 border-t border-[var(--border)] pt-6"
    >
      <div className="flex flex-col gap-0.5">
        <h2 className="text-sm font-semibold text-[var(--text-primary)]">
          다른 방식으로 추천받기
        </h2>
        <p className="text-xs text-[var(--text-caption)]">
          필터 대신 추천 방식 자체를 바꿔 봅니다.
        </p>
      </div>

      <IntentModeToggle
        selectedPersona={selectedPersona}
        onPersonaChange={onPersonaChange}
      />

      {/* (closes #1601 / 그룹화 #1712) 모임 사회자(P-D) 모드 진입 — "다 같이 즐길 곡"
          시퀀스 화면으로 **이동** 한다. 의도 모드 토글과 달리 페이지가 바뀌므로 "새 화면"
          라벨 + 화살표(›)로 이동형임을 명시해 혼자 부를 사용자의 오인을 줄인다. */}
      <Link
        href="/recommend/host"
        className="flex items-center justify-between gap-3 self-stretch rounded-[var(--radius-md)] border border-[var(--border)] px-4 py-3 text-left text-[var(--text-secondary)] transition-colors duration-[var(--duration-base)] hover:bg-[var(--bg-subtle)] focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)] focus-visible:ring-offset-2"
      >
        <span className="flex flex-col gap-0.5">
          <span className="flex items-center gap-2">
            <span className="text-sm font-medium text-[var(--text-primary)]">
              여럿이 함께 부르나요? 모임 사회자 모드
            </span>
            <span className="inline-flex items-center rounded-full bg-[var(--badge-neutral-bg)] px-2 py-0.5 text-[10px] font-medium text-[var(--badge-neutral-fg)]">
              새 화면
            </span>
          </span>
          <span className="text-xs text-[var(--text-caption)]">
            도입·고조·마무리 단계별 흐름으로 자리를 띄워 줍니다
          </span>
        </span>
        <span aria-hidden="true" className="text-[var(--text-tertiary)]">
          ›
        </span>
      </Link>
    </section>
  );
}
