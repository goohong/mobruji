"use client";

/**
 * 홈 페이지 (`/`, closes #275).
 *
 * 측정 상태에 따라 onboarding/CTA를 분기한다:
 *  - 측정 안 한 사용자(`voiceRangeId == null`): 4단계 흐름 안내 + "음역대 측정하기"
 *    primary CTA. 왜 측정이 필요한지 한 줄 부연.
 *  - 측정 한 사용자: "추천 받기" primary CTA + 음역대 요약(저음~고음, 음표명) +
 *    "다시 측정"(자동)·"직접 다시 설정"(수동) 보조 + 좋아요/북마크/이력 빠른 진입.
 *
 * SSR/hydration:
 *  - `useSessionStore`는 localStorage에서 hydrate 되므로 SSR/CSR mismatch를 피하려고
 *    클라이언트 마운트 이전엔 "측정 상태 불명" 변형(NewUserPanel)을 렌더한다.
 *    마운트 후 voiceRangeId가 있으면 ReturningUserPanel로 부드럽게 전환된다.
 *  - 노트명 요약은 `readVoiceRange(sessionId)` 결과를 React Query로 가져와 보여준다.
 *    fetch 실패 시 ID만 보여주는 graceful fallback 유지.
 */

import { useSyncExternalStore } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";

import { midiToKoreanNoteName } from "@/lib/notes";
import { readVoiceRange, type VoiceRangeResponse } from "@/lib/api/voice-range";
import { useSessionStore } from "@/store/session";
import { OnboardingIntentPicker } from "@/app/components/OnboardingIntentPicker";
import { BrandWordmark } from "@/components/brand/BrandWordmark";

/**
 * zustand persist hydration 완료 여부를 React에 구독시킨다.
 *
 * - 서버 렌더링 + 클라이언트 hydration 직후엔 `false` → 측정 상태 분기를 보류한다.
 * - localStorage 로드 완료 시 `true` 로 바뀌며 컴포넌트가 자동 리렌더된다.
 * - `useEffect + setState` 패턴 대신 `useSyncExternalStore` 를 쓰는 이유:
 *   React 컴파일러 / `react-hooks/set-state-in-effect` 규칙은 effect 내 setState
 *   호출을 막고 있다. 외부 store 구독은 useSyncExternalStore가 정석.
 */
function useHasHydratedSession(): boolean {
  return useSyncExternalStore(
    (callback) => useSessionStore.persist.onFinishHydration(callback),
    () => useSessionStore.persist.hasHydrated(),
    // SSR snapshot — 서버에서는 항상 "측정 안 함"으로 안전한 변형을 렌더한다.
    () => false,
  );
}

export default function Home() {
  const hasHydrated = useHasHydratedSession();
  const voiceRangeId = useSessionStore((state) => state.voiceRangeId);
  const hasMeasurement = hasHydrated && voiceRangeId !== null;

  /*
   * ADR-0018 단계 4 — homepage 토큰 swap.
   *
   * PR 2 (#1137) — first-paint 핵심 5요소 swap:
   *  1) <main> 배경 + padding (--bg-subtle + --page-padding-*)
   *  2) h1 / header 부제 (--text-primary + --text-secondary)
   *  3) NewUser/Returning 카드 (--bg-base + --shadow-sm + --border + --radius-lg)
   *  4) primary CTA (--brand-500 / --brand-600 + --shadow-brand)
   *
   * PR 8 (#1170) — `--text-caption` 통합 (보조 caption 페어 일괄 swap).
   *
   * PR 11 (#1044) — 잔여 zinc hardcode swap (본 PR):
   *  5) NewUser/Returning 패널 h2/p (--text-primary + --text-secondary)
   *  6) FlowStep 텍스트 라벨 (--text-label)
   *  7) 보조 CTA "직접 입력으로 시작" / "음역대 다시 측정" (--cta-secondary-*)
   *  8) VoiceRangeSummary 박스 3종 (--badge-neutral-bg + --text-* 재사용)
   *  9) SecondaryNav 4 link (--cta-secondary-* + --border-input)
   * 10) FlowStep index chip (--surface-step-*)
   * 11) disclaimer "익명 세션..." (--text-disclaimer)
   *
   * 다크 모드: tokens.css 의 `:where(html.dark)` selector 가 토큰값을 자동
   * swap → 사용처에서 `dark:` prefix 제거.
   */
  return (
    <main className="flex flex-1 flex-col items-center bg-[var(--bg-subtle)] px-[var(--page-padding-x)] py-[var(--page-padding-y)]">
      <div className="w-full max-w-md flex flex-col items-center gap-8">
        <header className="flex flex-col items-center space-y-3 text-center">
          <BrandWordmark lang="ko" size="md" theme="auto" />
          <h1 className="text-3xl font-semibold leading-tight text-[var(--text-primary)] sm:text-4xl">
            오늘 노래방, 뭐 부르지?
          </h1>
          <p className="text-base text-[var(--text-secondary)]">
            내 음역대만 알려주면, 부르기 편한 곡을 추천해드려요.
          </p>
        </header>

        {hasMeasurement ? <ReturningUserPanel /> : <NewUserPanel />}

        <SecondaryNav />

        <p className="text-xs text-[var(--text-disclaimer)]">
          익명 세션으로 동작합니다. 회원가입 없음.
        </p>
      </div>
    </main>
  );
}

/**
 * 측정 안 한(또는 마운트 전) 사용자에게 노출되는 패널.
 *
 * - 1→4 단계 흐름을 ordered list로 안내해 첫 진입 사용자가 전체 그림을 파악하게 한다.
 * - 진입 분기는 `<OnboardingIntentPicker>` 로 위임 — 3 페르소나 경로 카드 + "그냥
 *   둘러보기" 보조 경로. 의도별로 측정 진입 카피를 다르게 옷 입혀 D3 표기 충격(P1)과
 *   동기 불일치를 완화한다 (first-user-onboarding-flow.md §2·§3, PR 2).
 */
function NewUserPanel() {
  return (
    <section
      aria-labelledby="home-onboarding-heading"
      className="flex w-full flex-col gap-5 rounded-[var(--radius-lg)] bg-[var(--bg-base)] p-6 shadow-[var(--shadow-sm)] ring-1 ring-[var(--border)]"
    >
      <div className="space-y-2">
        <h2
          id="home-onboarding-heading"
          className="text-lg font-semibold text-[var(--text-primary)]"
        >
          무엇을 도와드릴까요?
        </h2>
        <p className="text-sm text-[var(--text-secondary)]">
          원하는 걸 고르면 그에 맞춰 안내해드려요. 아무거나 골라도 막다른 길은 없어요.
        </p>
      </div>

      <ol
        aria-label="이용 단계"
        className="flex flex-col gap-2 text-sm text-[var(--text-label)]"
      >
        <FlowStep index={1} label="음역대 측정 (자동 1분 or 직접 입력)" />
        <FlowStep index={2} label="분위기·성별·속도 선택" />
        <FlowStep index={3} label="맞춤 추천 받기" />
        <FlowStep index={4} label="좋아요·북마크로 다시 찾아보기" />
      </ol>

      <OnboardingIntentPicker />
    </section>
  );
}

/**
 * 이미 측정한 사용자에게 노출되는 패널.
 *
 * - primary CTA를 "추천 받기"로 바꿔 측정 단계를 다시 거치지 않도록 한다.
 * - 측정한 음역대를 음표명(C3 ~ A4 등)으로 보여줘 사용자가 "내가 입력한 값이 맞나"
 *   확인 가능하게 한다. BE fetch 실패 시 음역대 ID 만 노출하는 fallback 유지.
 * - "다시 측정"(자동)과 "직접 다시 설정"(수동 입력)을 secondary로 나란히 두어 잘못
 *   입력했거나 시간이 흘러 조정하고 싶을 때 두 경로 모두로 재진입할 수 있게 한다.
 */
function ReturningUserPanel() {
  const voiceRangeId = useSessionStore((state) => state.voiceRangeId);
  const sessionId = useSessionStore((state) => state.sessionId);

  const voiceRangeQuery = useQuery<VoiceRangeResponse>({
    queryKey: ["voice-range", sessionId],
    queryFn: () => readVoiceRange(sessionId as string),
    enabled: Boolean(sessionId),
    staleTime: 60_000,
    // BE 호출 실패 시 빠르게 ID fallback으로 떨어져야 사용자가 멈춰 보이지 않는다.
    // 전역 default(QueryClientProvider) 정책을 그대로 따른다.
  });

  return (
    <section
      aria-labelledby="home-returning-heading"
      className="flex w-full flex-col gap-5 rounded-[var(--radius-lg)] bg-[var(--bg-base)] p-6 shadow-[var(--shadow-sm)] ring-1 ring-[var(--border)]"
    >
      <div className="space-y-2">
        <h2
          id="home-returning-heading"
          className="text-lg font-semibold text-[var(--text-primary)]"
        >
          다시 오신 걸 환영해요
        </h2>
        <p className="text-sm text-[var(--text-secondary)]">
          음역대 정보가 저장돼 있어요. 바로 추천받거나, 필요하면 다시 측정할 수 있어요.
        </p>
      </div>

      <VoiceRangeSummary
        voiceRangeId={voiceRangeId}
        voiceRange={voiceRangeQuery.data ?? null}
        isPending={voiceRangeQuery.isPending && Boolean(sessionId)}
      />

      <div className="flex flex-col gap-2">
        <Link
          href="/recommend"
          className="inline-flex h-12 w-full items-center justify-center rounded-full bg-[var(--brand-500)] px-6 text-base font-medium text-white transition-colors duration-[var(--duration-base)] hover:bg-[var(--brand-600)] hover:shadow-[var(--shadow-brand)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--brand-500)] focus-visible:ring-offset-2"
        >
          추천 받기
        </Link>
        <Link
          href="/voice-range/auto"
          className="inline-flex h-11 w-full items-center justify-center rounded-full border border-[var(--cta-secondary-border)] bg-[var(--cta-secondary-bg)] px-6 text-sm font-medium text-[var(--cta-secondary-fg)] transition-colors hover:bg-[var(--cta-secondary-bg-hover)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)]"
        >
          음역대 다시 측정
        </Link>
        <Link
          href="/voice-range"
          className="inline-flex h-11 w-full items-center justify-center rounded-full border border-[var(--cta-secondary-border)] bg-[var(--cta-secondary-bg)] px-6 text-sm font-medium text-[var(--cta-secondary-fg)] transition-colors hover:bg-[var(--cta-secondary-bg-hover)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)]"
        >
          직접 다시 설정
        </Link>
      </div>
    </section>
  );
}

type VoiceRangeSummaryProps = {
  voiceRangeId: number | null;
  voiceRange: VoiceRangeResponse | null;
  isPending: boolean;
};

/**
 * 저장된 음역대를 음표명으로 짧게 보여준다.
 *
 * 표시 우선순위:
 *  1) BE 응답이 도착하면 `C3 ~ A4 (저장됨)` 형태로 보여줌.
 *  2) 로딩 중이면 "음역대 불러오는 중…".
 *  3) BE fetch 실패 또는 sessionId 부재면 ID만 노출(`저장된 음역대 #42`) — 사용자가
 *     "추천 받기"는 그대로 누를 수 있어야 하므로 막지 않는다.
 */
function VoiceRangeSummary({
  voiceRangeId,
  voiceRange,
  isPending,
}: VoiceRangeSummaryProps) {
  if (voiceRange) {
    const lowNote = midiToKoreanNoteName(voiceRange.lowestNoteMidi);
    const highNote = midiToKoreanNoteName(voiceRange.highestNoteMidi);
    return (
      <p
        aria-label="저장된 음역대"
        className="rounded-lg bg-[var(--badge-neutral-bg)] px-3 py-2 text-sm text-[var(--badge-neutral-fg)]"
      >
        저장된 음역대 <span className="font-semibold">{lowNote} ~ {highNote}</span>
      </p>
    );
  }
  if (isPending) {
    return (
      <p
        role="status"
        aria-live="polite"
        className="rounded-lg bg-[var(--badge-neutral-bg)] px-3 py-2 text-sm text-[var(--text-caption)]"
      >
        음역대 불러오는 중…
      </p>
    );
  }
  // fallback: BE 호출 실패 또는 sessionId 부재
  return (
    <p
      aria-label="저장된 음역대 ID"
      className="rounded-lg bg-[var(--badge-neutral-bg)] px-3 py-2 text-xs text-[var(--text-caption)]"
    >
      저장된 음역대 #{voiceRangeId}
    </p>
  );
}

/**
 * 좋아요/북마크/이력/검색 빠른 진입 navigation.
 *
 * 측정 여부와 무관하게 노출 — 검색은 측정 없이도 진입 가능한 경로, 좋아요/북마크/이력은
 * 빈 상태(empty)도 친화 메시지를 가지고 있어 측정 안 한 사용자가 눌러도 막다른 길이
 * 아니다. 따라서 분기 바깥에 둔다.
 *
 * 라벨은 글로벌 nav(BottomNav/DesktopNav)와 통일한다 (closes #1717) — /history 는
 * 탭 라벨과 동일하게 "이력". 측정/추천은 위 primary CTA 가 이미 담당하므로 여기서는
 * 콘텐츠 목적지(검색/이력/좋아요/북마크)만 둔다.
 */
function SecondaryNav() {
  const items: Array<{ href: string; label: string }> = [
    { href: "/songs", label: "곡 검색" },
    { href: "/history", label: "이력" },
    { href: "/likes", label: "좋아요" },
    { href: "/bookmarks", label: "북마크" },
  ];
  return (
    <nav aria-label="빠른 진입" className="w-full">
      <ul className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        {items.map((item) => (
          <li key={item.href}>
            <Link
              href={item.href}
              className="inline-flex h-11 w-full items-center justify-center rounded-full border border-[var(--border-input)] bg-[var(--cta-secondary-bg)] px-3 text-sm font-medium text-[var(--cta-secondary-fg)] transition-colors hover:bg-[var(--cta-secondary-bg-hover)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)]"
            >
              {item.label}
            </Link>
          </li>
        ))}
      </ul>
    </nav>
  );
}

type FlowStepProps = {
  index: number;
  label: string;
};

function FlowStep({ index, label }: FlowStepProps) {
  return (
    <li className="flex items-start gap-3">
      <span
        aria-hidden="true"
        className="mt-0.5 inline-flex h-5 w-5 flex-shrink-0 items-center justify-center rounded-full bg-[var(--surface-step-bg)] text-xs font-semibold text-[var(--surface-step-fg)]"
      >
        {index}
      </span>
      <span>{label}</span>
    </li>
  );
}
