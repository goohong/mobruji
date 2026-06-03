"use client";

/**
 * 온보딩 진입 분기 카드 (first-user-onboarding-flow.md §5-6, PR 2).
 *
 * 첫 진입 사용자에게 3 페르소나 진입 경로(`PersonaEntryPath`)를 카드로 노출하고,
 * 의도를 강제하지 않는 "그냥 둘러보기" 보조 경로를 함께 둔다. 카드를 고르면
 * 온보딩 store 에 경로를 기록(중간 재진입 대비)한 뒤 측정 화면으로 라우팅한다.
 *
 * 측정 방식 분기 (directive #1511):
 *   - 카드를 고르면 측정 방식 선택 화면(`/voice-range/method`)으로 보낸다. 거기서
 *     '자동(마이크) 측정' vs '직접 입력' 을 사용자가 직접 고른다 — 진입만으로 마이크
 *     권한/측정을 강제하지 않는다. 경로별 목적지는 `PATH_DESTINATION` 한 곳에 모아
 *     두어, F1/F2/F3 자식 spec 진척 시 한 줄로 갈아끼운다.
 *   - 측정 진입 카피만 경로별로 다르게 옷 입혀(입문=안심/가이드, 연습=목표,
 *     분위기=가벼운 마지막 단계) D3 표기 충격(P1)과 동기 불일치를 완화한다.
 */

import Link from "next/link";

import {
  useOnboardingStore,
  type PersonaEntryPath,
} from "@/store/onboarding";

type PersonaCard = {
  path: PersonaEntryPath;
  title: string;
  description: string;
};

/**
 * 경로별 측정 진입 카피. 페르소나 정의 SoT = user-persona-and-pain-points.md §2.
 * 카피 톤은 spec §3 "측정 단계 진입 카피를 경로별로 다르게" 요구사항을 따른다.
 */
const PERSONA_CARDS: readonly PersonaCard[] = [
  {
    path: "BEGINNER",
    title: "내 목소리부터 알아보기",
    description: "노래방이 처음이어도 괜찮아요. 차근차근 내 음역대부터 확인해요.",
  },
  {
    path: "PRACTICE",
    title: "발성·고음 연습할 곡 찾기",
    description: "발성·고음 트레이닝에 좋은 곡으로 연습해요. 먼저 음역대를 확인할게요.",
  },
  {
    path: "MOOD",
    title: "분위기 띄울 곡 찾기",
    description: "회식·모임 분위기를 살릴 곡을 골라요. 목소리는 마지막에 가볍게.",
  },
];

/**
 * 경로별 라우팅 목적지.
 *
 * 현재는 전부 측정 방식 선택 화면(`/voice-range/method`)으로 보낸다. F1/F2/F3
 * 화면이 생기면 이 매핑만 갈아끼운다(예: BEGINNER → `/voice-range/method?tour=1`).
 */
const PATH_DESTINATION: Record<PersonaEntryPath, string> = {
  BEGINNER: "/voice-range/method",
  PRACTICE: "/voice-range/method",
  MOOD: "/voice-range/method",
};

export function OnboardingIntentPicker() {
  const selectEntryPath = useOnboardingStore((state) => state.selectEntryPath);
  const selectBrowse = useOnboardingStore((state) => state.selectBrowse);

  return (
    <div className="flex flex-col gap-4">
      <ul
        aria-label="진입 경로 선택"
        className="flex flex-col gap-3"
      >
        {PERSONA_CARDS.map((card) => (
          <li key={card.path}>
            <Link
              href={PATH_DESTINATION[card.path]}
              onClick={() => selectEntryPath(card.path)}
              className="flex flex-col gap-1 rounded-[var(--radius-lg)] border border-[var(--border-input)] bg-[var(--cta-secondary-bg)] px-4 py-3 text-left transition-colors hover:bg-[var(--cta-secondary-bg-hover)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)]"
            >
              <span className="text-base font-semibold text-[var(--text-primary)]">
                {card.title}
              </span>
              <span className="text-sm text-[var(--text-secondary)]">
                {card.description}
              </span>
            </Link>
          </li>
        ))}
      </ul>

      <div className="flex flex-col gap-2">
        <Link
          href="/voice-range/method"
          onClick={() => selectBrowse()}
          className="inline-flex h-11 w-full items-center justify-center rounded-full border border-[var(--cta-secondary-border)] bg-[var(--cta-secondary-bg)] px-6 text-sm font-medium text-[var(--cta-secondary-fg)] transition-colors hover:bg-[var(--cta-secondary-bg-hover)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)]"
        >
          그냥 둘러보기
        </Link>
        <Link
          href="/voice-range"
          onClick={() => selectBrowse()}
          className="inline-flex h-11 w-full items-center justify-center text-sm font-medium text-[var(--cta-secondary-fg)] underline-offset-4 transition-colors hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)]"
        >
          직접 입력으로 시작
        </Link>
      </div>
    </div>
  );
}
