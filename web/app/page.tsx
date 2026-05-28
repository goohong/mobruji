"use client";

/**
 * 홈 페이지 (`/`, closes #275).
 *
 * 측정 상태에 따라 onboarding/CTA를 분기한다:
 *  - 측정 안 한 사용자(`voiceRangeId == null`): 4단계 흐름 안내 + "음역대 측정하기"
 *    primary CTA. 왜 측정이 필요한지 한 줄 부연.
 *  - 측정 한 사용자: "추천 받기" primary CTA + 음역대 요약(저음~고음, 음표명) +
 *    "다시 측정" 보조 + 좋아요/북마크/이력 빠른 진입.
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

import { midiToCombinedNoteName } from "@/lib/notes";
import { readVoiceRange, type VoiceRangeResponse } from "@/lib/api/voice-range";
import { useSessionStore } from "@/store/session";

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
   * ADR-0018 단계 4 PR 2 — homepage 토큰 swap 1차.
   *
   * 본 PR 에서 swap 한 요소 (총 8개):
   *  1) <main> 배경 : `bg-zinc-50 ... dark:bg-zinc-950` → `bg-[var(--bg-subtle)]`
   *  2) <main> padding : `px-6 py-12` → `px-[var(--page-padding-x)] py-[var(--page-padding-y)]`
   *  3) h1 색상 : `text-zinc-900 dark:text-zinc-50` → `text-[var(--text-primary)]`
   *  4) header 부제 색상 : `text-zinc-600 dark:text-zinc-400` → `text-[var(--text-secondary)]`
   *  5) NewUser 카드 배경 + ring + radius : `bg-white ring-zinc-200 rounded-2xl ...` → tokens
   *  6) Returning 카드 동일 패턴
   *  7) NewUser primary CTA : `bg-zinc-900 ...` (검정) → `bg-[var(--brand-500)] hover:bg-[var(--brand-600)]` (브랜드 indigo)
   *  8) Returning primary CTA 동일 패턴
   *
   * 다크 모드: tokens.css 의 `:where(html.dark)` selector 가 토큰값을 자동
   * swap 하므로 swap 한 요소에서는 `dark:` prefix 를 제거할 수 있다. 보조 CTA /
   * SecondaryNav / FlowStep / VoiceRangeSummary 는 후속 PR (단계 4 PR 3+) 에
   * 양보 — 본 PR scope 는 "first-paint 핵심 5요소 (배경/h1/카드/CTA/padding)" 로
   * 한정해 회귀 표면 최소화.
   */
  return (
    <main className="flex flex-1 flex-col items-center bg-[var(--bg-subtle)] px-[var(--page-padding-x)] py-[var(--page-padding-y)]">
      <div className="w-full max-w-md flex flex-col items-center gap-8">
        <header className="space-y-3 text-center">
          <p className="text-sm font-medium uppercase tracking-widest text-[var(--text-caption)]">
            mobruji
          </p>
          <h1 className="text-3xl font-semibold leading-tight text-[var(--text-primary)] sm:text-4xl">
            오늘 노래방, 뭐 부르지?
          </h1>
          <p className="text-base text-[var(--text-secondary)]">
            내 음역대만 알려주면, 부르기 편한 곡을 추천해드려요.
          </p>
        </header>

        {hasMeasurement ? <ReturningUserPanel /> : <NewUserPanel />}

        <SecondaryNav />

        <p className="text-xs text-zinc-500 dark:text-zinc-500">
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
 * - primary CTA는 자동 측정. 직접 입력은 보조 link로 한 단계 내린다 — 자동 측정이
 *   현재 가장 정확하고 ux 마찰이 적기 때문에 (PR #271 폴리싱 완료).
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
          className="text-lg font-semibold text-zinc-900 dark:text-zinc-50"
        >
          시작하기
        </h2>
        <p className="text-sm text-zinc-600 dark:text-zinc-400">
          음역대를 알아야 부르기 편한 키의 곡만 추려서 보여드릴 수 있어요.
        </p>
      </div>

      <ol
        aria-label="이용 단계"
        className="flex flex-col gap-2 text-sm text-zinc-700 dark:text-zinc-300"
      >
        <FlowStep index={1} label="음역대 측정 (자동 1분 or 직접 입력)" />
        <FlowStep index={2} label="분위기·성별·속도 선택" />
        <FlowStep index={3} label="맞춤 추천 받기" />
        <FlowStep index={4} label="좋아요·북마크로 다시 찾아보기" />
      </ol>

      <div className="flex flex-col gap-2">
        <Link
          href="/voice-range/auto"
          className="inline-flex h-12 w-full items-center justify-center rounded-full bg-[var(--brand-500)] px-6 text-base font-medium text-white transition-colors duration-[var(--duration-base)] hover:bg-[var(--brand-600)] hover:shadow-[var(--shadow-brand)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--brand-500)] focus-visible:ring-offset-2"
        >
          음역대 측정하기
        </Link>
        <Link
          href="/voice-range"
          className="inline-flex h-11 w-full items-center justify-center rounded-full border border-zinc-300 bg-white px-6 text-sm font-medium text-zinc-700 transition-colors hover:bg-zinc-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-zinc-500 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-200 dark:hover:bg-zinc-800"
        >
          직접 입력으로 시작
        </Link>
      </div>
    </section>
  );
}

/**
 * 이미 측정한 사용자에게 노출되는 패널.
 *
 * - primary CTA를 "추천 받기"로 바꿔 측정 단계를 다시 거치지 않도록 한다.
 * - 측정한 음역대를 음표명(C3 ~ A4 등)으로 보여줘 사용자가 "내가 입력한 값이 맞나"
 *   확인 가능하게 한다. BE fetch 실패 시 음역대 ID 만 노출하는 fallback 유지.
 * - "다시 측정"은 secondary로 두어 잘못 입력했거나 시간이 흘러 조정하고 싶을 때 진입.
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
          className="text-lg font-semibold text-zinc-900 dark:text-zinc-50"
        >
          다시 오신 걸 환영해요
        </h2>
        <p className="text-sm text-zinc-600 dark:text-zinc-400">
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
          className="inline-flex h-11 w-full items-center justify-center rounded-full border border-zinc-300 bg-white px-6 text-sm font-medium text-zinc-700 transition-colors hover:bg-zinc-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-zinc-500 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-200 dark:hover:bg-zinc-800"
        >
          음역대 다시 측정
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
    const lowNote = midiToCombinedNoteName(voiceRange.lowestNoteMidi);
    const highNote = midiToCombinedNoteName(voiceRange.highestNoteMidi);
    return (
      <p
        aria-label="저장된 음역대"
        className="rounded-lg bg-zinc-100 px-3 py-2 text-sm text-zinc-700 dark:bg-zinc-800 dark:text-zinc-200"
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
        className="rounded-lg bg-zinc-100 px-3 py-2 text-sm text-[var(--text-caption)] dark:bg-zinc-800"
      >
        음역대 불러오는 중…
      </p>
    );
  }
  // fallback: BE 호출 실패 또는 sessionId 부재
  return (
    <p
      aria-label="저장된 음역대 ID"
      className="rounded-lg bg-zinc-100 px-3 py-2 text-xs text-[var(--text-caption)] dark:bg-zinc-800"
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
 */
function SecondaryNav() {
  const items: Array<{ href: string; label: string }> = [
    { href: "/songs", label: "곡 검색" },
    { href: "/history", label: "받은 추천" },
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
              className="inline-flex h-11 w-full items-center justify-center rounded-full border border-zinc-200 bg-white px-3 text-sm font-medium text-zinc-700 transition-colors hover:bg-zinc-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-zinc-500 dark:border-zinc-800 dark:bg-zinc-900 dark:text-zinc-200 dark:hover:bg-zinc-800"
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
        className="mt-0.5 inline-flex h-5 w-5 flex-shrink-0 items-center justify-center rounded-full bg-zinc-200 text-xs font-semibold text-zinc-700 dark:bg-zinc-800 dark:text-zinc-200"
      >
        {index}
      </span>
      <span>{label}</span>
    </li>
  );
}
