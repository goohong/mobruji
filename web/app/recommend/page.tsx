"use client";

/**
 * 추천 결과 페이지.
 *
 * docs/features/recommendation-algorithm-v1.md §5-2 응답 사용:
 *   - 세션에 저장된 sessionId 기반으로 voice-range를 조회해 음역 low/high를 얻고,
 *   - POST /api/v1/recommendations 호출 → 결과 카드 리스트 노출.
 *
 * 세션 없음/음역대 미등록인 경우 음역대 입력 페이지로 안내한다.
 *
 * 이슈 #75 / PR #76 (2026-05-21):
 *   - 카드 렌더는 `./components/SongCard`로 분리. 음역대 막대 그래프 시각화는 폐기.
 *   - 로딩 중에는 SongCardSkeleton 다수 노출 → 결과 자리에 대한 공간 인지 향상.
 *   - 빈 결과 시 음역대 재입력 CTA 강조.
 *
 * 이슈 #83 #84 / PR #85 (2026-05-21):
 *   - "다른 곡 추천받기" 버튼 추가. 클릭 시 현재 보여준 곡 ID들을 store
 *     `excludedSongIds`에 누적하고 다음 createRecommendation 호출에 함께 전달.
 *   - BE는 `excludeSongIds`를 SeedDeriver 입력에 포함(PR #64) + entity 영속화(PR #74)
 *     하므로 같은 voiceRange/sessionId라도 매번 다른 결과를 결정성 있게 반환한다.
 *   - 응답 곡 수가 0인 경우(이미 누적 셋이 카탈로그를 다 덮은 경우) "더 이상
 *     추천할 곡이 없어요" fallback을 노출하고 다시 버튼은 숨긴다.
 *
 * 이슈 #282 (2026-05-22):
 *   - 헤더에 voice-range source method 뱃지(MIC/OCTAVE/SELF) 노출.
 *   - MIC_MEASURE 소스일 때만 "마이크로 다시 측정" 1차 액션 링크 강조.
 *   - voice-range mutation onSuccess 가 react-query 캐시에 응답을 prime 하므로
 *     이 페이지의 useQuery 는 캐시 히트로 즉시 추천 mutation 발화.
 */

import { useEffect } from "react";
import Link from "next/link";
import { useMutation, useQuery } from "@tanstack/react-query";

import { ApiError } from "@/lib/api/client";
import {
  createRecommendation,
  RecommendationResponse,
} from "@/lib/api/recommendation";
import {
  readVoiceRange,
  VoiceRangeResponse,
  VoiceRangeSourceMethod,
} from "@/lib/api/voice-range";
import { midiToNoteName } from "@/lib/notes";
import { useHistoryStore } from "@/store/history";
import { useSessionStore } from "@/store/session";
import { Button } from "@/components/ui";

import { SongCard, SongCardSkeleton } from "./components/SongCard";

const SKELETON_COUNT = 4;

export default function RecommendPage() {
  const sessionId = useSessionStore((state) => state.sessionId);

  if (!sessionId) {
    return <NoSessionFallback />;
  }

  return <RecommendContent sessionId={sessionId} />;
}

type RecommendContentProps = {
  sessionId: string;
};

function RecommendContent({ sessionId }: RecommendContentProps) {
  const voiceRangeQuery = useQuery<VoiceRangeResponse, Error>({
    queryKey: ["voice-range", sessionId],
    queryFn: () => readVoiceRange(sessionId),
  });

  type RecommendationInput = {
    sessionId: string;
    voiceRangeLow: number;
    voiceRangeHigh: number;
    excludeSongIds: number[];
  };

  const excludedSongIds = useSessionStore((state) => state.excludedSongIds);
  const voiceRangeIdFromStore = useSessionStore((state) => state.voiceRangeId);
  const appendExcluded = useSessionStore((state) => state.appendExcluded);
  const appendHistory = useHistoryStore((state) => state.appendRecommendation);

  const recommendationMutation = useMutation<
    RecommendationResponse,
    Error,
    RecommendationInput
  >({
    mutationFn: (input) =>
      createRecommendation({
        sessionId: input.sessionId,
        voiceRangeLow: input.voiceRangeLow,
        voiceRangeHigh: input.voiceRangeHigh,
        excludeSongIds: input.excludeSongIds,
      }),
    onSuccess: (data, variables) => {
      // 다음 "다시 추천" 호출에 누적 전달되도록 결과 곡 ID들을 store에 push.
      // 빈 응답이라도 호출에 문제는 없지만 빈 ids는 store 내부에서 no-op.
      const ids = data.recommendations.map((rec) => rec.song.id);
      if (ids.length > 0) {
        appendExcluded(ids);
      }
      // 추천 히스토리(closes #134)에 누적. 빈 응답은 히스토리에 남기지 않는다 —
      // 사용자가 "다시 보기"를 눌렀을 때 빈 카드만 보는 의미 없는 항목이 쌓이지 않게.
      if (data.recommendations.length > 0) {
        // 음역 발전 추적 카드(closes #170)용으로 추천 시점의 lowest/highest MIDI를
        // 함께 보관한다. voiceRangeQuery.data 가 onSuccess 시점에 정의돼 있을
        // 가능성이 매우 높지만 (이 mutation 은 voice-range 가 성공해야만 호출됨),
        // 방어적으로 optional 로 spread 한다.
        const snapshot = voiceRangeQuery.data;
        appendHistory({
          requestId: data.requestId,
          voiceRangeId: voiceRangeIdFromStore,
          songs: data.recommendations,
          // 이 추천을 만든 시점의 누적 제외 셋 스냅샷. variables 에 담겨 들어온 값
          // (호출 시점 store snapshot)이라 호출 후 store 변경에 영향받지 않는다.
          excludedSongIds: variables.excludeSongIds,
          voiceRangeLowMidi: variables.voiceRangeLow,
          voiceRangeHighMidi: variables.voiceRangeHigh,
          voiceRangeSourceMethod: snapshot?.sourceMethod,
        });
      }
    },
  });

  // 음역대 조회 성공 시 자동으로 추천 호출 (1회).
  // 객체 의존성으로 인한 useEffect 무한 재실행 함정을 피하기 위해
  // primitive 필드와 mutation의 stable 함수 ref만 의존성에 둔다.
  // (TanStack Query는 .mutate 함수 ref는 안정적으로 보장한다.)
  //
  // excludedSongIds는 자동 트리거 의존성에서 **제외**한다. mutate 핸들러
  // 시점에 최신 값을 useSessionStore.getState()로 읽으면 useEffect를 다시
  // 발화시키지 않으면서도 누적 리스트를 반영할 수 있다.
  const isVoiceRangeSuccess = voiceRangeQuery.isSuccess;
  const voiceRangeSessionId = voiceRangeQuery.data?.sessionId;
  const voiceRangeLow = voiceRangeQuery.data?.lowestNoteMidi;
  const voiceRangeHigh = voiceRangeQuery.data?.highestNoteMidi;
  const isRecommendationIdle = recommendationMutation.isIdle;
  const triggerRecommendation = recommendationMutation.mutate;

  useEffect(() => {
    if (!isVoiceRangeSuccess) {
      return;
    }
    if (
      voiceRangeSessionId === undefined ||
      voiceRangeLow === undefined ||
      voiceRangeHigh === undefined
    ) {
      return;
    }
    if (!isRecommendationIdle) {
      return;
    }
    triggerRecommendation({
      sessionId: voiceRangeSessionId,
      voiceRangeLow,
      voiceRangeHigh,
      excludeSongIds: useSessionStore.getState().excludedSongIds,
    });
  }, [
    isVoiceRangeSuccess,
    voiceRangeSessionId,
    voiceRangeLow,
    voiceRangeHigh,
    isRecommendationIdle,
    triggerRecommendation,
  ]);

  if (voiceRangeQuery.isLoading) {
    return <StatusShell title="음역대를 불러오는 중..." />;
  }

  if (voiceRangeQuery.isError) {
    const error = voiceRangeQuery.error;
    if (error instanceof ApiError && error.status === 404) {
      return <NoSessionFallback />;
    }
    return (
      <StatusShell
        title="음역대 정보를 불러오지 못했습니다"
        description={error.message}
        ctaHref="/voice-range"
        ctaLabel="다시 입력하기"
      />
    );
  }

  const voiceRange = voiceRangeQuery.data;
  if (!voiceRange) {
    return <NoSessionFallback />;
  }

  const handleRecommendAgain = () => {
    recommendationMutation.mutate({
      sessionId: voiceRange.sessionId,
      voiceRangeLow: voiceRange.lowestNoteMidi,
      voiceRangeHigh: voiceRange.highestNoteMidi,
      // 버튼 클릭 시점의 최신 누적 리스트를 그대로 전달.
      excludeSongIds: useSessionStore.getState().excludedSongIds,
    });
  };

  return (
    <main className="flex flex-1 flex-col items-center bg-zinc-50 px-6 py-12 dark:bg-zinc-950">
      <div className="w-full max-w-2xl flex flex-col gap-8">
        <header className="space-y-2">
          <p className="text-xs font-medium uppercase tracking-widest text-zinc-500 dark:text-zinc-400">
            Step 2
          </p>
          <h1 className="text-2xl font-semibold text-zinc-900 dark:text-zinc-50">
            추천 결과
          </h1>
          <div className="flex flex-wrap items-center gap-2">
            <p className="text-sm text-zinc-600 dark:text-zinc-400">
              내 음역대: {midiToNoteName(voiceRange.lowestNoteMidi)} ~{" "}
              {midiToNoteName(voiceRange.highestNoteMidi)}
            </p>
            <SourceMethodBadge sourceMethod={voiceRange.sourceMethod} />
          </div>
          <div className="flex flex-wrap items-center gap-x-4 gap-y-1 pt-2">
            {/* (closes #282) MIC 측정 결과면 "마이크로 다시 측정" 을 1차 액션으로
                강조한다. 자동 측정 결과를 보던 사용자가 "조금 더 끝까지 내볼까?"
                할 때 한 번 클릭으로 같은 흐름에 다시 들어가게 한다. */}
            {voiceRange.sourceMethod === "MIC_MEASURE" ? (
              <Link
                href="/voice-range/auto"
                className="text-sm font-medium text-zinc-900 underline-offset-4 hover:underline dark:text-zinc-50"
              >
                마이크로 다시 측정
              </Link>
            ) : null}
            <Link
              href="/voice-range"
              className="text-sm font-medium text-zinc-700 underline-offset-4 hover:underline dark:text-zinc-300"
            >
              음역대 다시 입력
            </Link>
          </div>
        </header>

        <RecommendationList
          isPending={
            recommendationMutation.isPending || recommendationMutation.isIdle
          }
          error={recommendationMutation.error}
          data={recommendationMutation.data}
          onRetry={handleRecommendAgain}
          onRecommendAgain={handleRecommendAgain}
          excludedCount={excludedSongIds.length}
          userVoiceRangeLow={voiceRange.lowestNoteMidi}
          userVoiceRangeHigh={voiceRange.highestNoteMidi}
        />
      </div>
    </main>
  );
}

type RecommendationListProps = {
  isPending: boolean;
  error: Error | null;
  data: RecommendationResponse | undefined;
  onRetry: () => void;
  onRecommendAgain: () => void;
  excludedCount: number;
  /**
   * 사용자 음역대 — 추천 카드의 "자세히 보기" 패널에서 음역 적합 점수를 계산할 때 사용.
   * (closes #141) 추천 컨텍스트에서는 항상 알 수 있는 값이라 필수로 받는다.
   */
  userVoiceRangeLow: number;
  userVoiceRangeHigh: number;
};

function RecommendationList({
  isPending,
  error,
  data,
  onRetry,
  onRecommendAgain,
  excludedCount,
  userVoiceRangeLow,
  userVoiceRangeHigh,
}: RecommendationListProps) {
  if (isPending) {
    return (
      <ul
        aria-busy="true"
        aria-label="추천 결과 로딩 중"
        className="flex flex-col gap-3"
      >
        {Array.from({ length: SKELETON_COUNT }).map((_, idx) => (
          <SongCardSkeleton key={idx} />
        ))}
      </ul>
    );
  }

  if (error) {
    return (
      <div className="flex flex-col gap-3 rounded-2xl border border-red-200 bg-red-50 p-4 dark:border-red-900 dark:bg-red-950">
        <p className="text-sm text-red-700 dark:text-red-200">
          추천을 불러오지 못했습니다.{" "}
          {error instanceof ApiError
            ? `${error.status}: ${error.message}`
            : error.message}
        </p>
        <Button
          variant="danger"
          size="sm"
          onClick={onRetry}
          className="self-start"
        >
          다시 시도
        </Button>
      </div>
    );
  }

  // data가 정의됐는데 비어 있는 경우 = 카탈로그를 누적 제외 셋이 모두 덮은 케이스.
  // 음역대 재입력 안내로 흐름을 끊는다. "다시 추천" 버튼은 숨긴다.
  if (data && data.recommendations.length === 0) {
    return (
      <div
        role="status"
        className="flex flex-col items-start gap-3 rounded-2xl border border-zinc-200 bg-white p-5 dark:border-zinc-800 dark:bg-zinc-900"
      >
        <p className="text-sm text-zinc-700 dark:text-zinc-300">
          더 이상 추천할 곡이 없어요. 음역대를 다시 입력해 보세요.
        </p>
        <Link
          href="/voice-range"
          className="inline-flex h-10 items-center justify-center rounded-full bg-zinc-900 px-4 text-sm font-medium text-white hover:bg-zinc-700 dark:bg-zinc-50 dark:text-zinc-900 dark:hover:bg-zinc-200"
        >
          음역대 다시 입력
        </Link>
      </div>
    );
  }

  if (!data) {
    // 자동 mutation이 트리거되기 전 idle 직후 한 프레임 — 안전망.
    return null;
  }

  return (
    <div className="flex flex-col gap-4">
      <ul className="flex flex-col gap-3">
        {data.recommendations.map((item) => (
          <SongCard
            key={item.song.id}
            item={item}
            href={`/songs/${item.song.id}`}
            userVoiceRange={{
              lowMidi: userVoiceRangeLow,
              highMidi: userVoiceRangeHigh,
            }}
          />
        ))}
      </ul>
      <div className="flex flex-col items-stretch gap-1">
        <Button
          variant="primary"
          size="md"
          onClick={onRecommendAgain}
          className="h-11"
        >
          다른 곡 추천받기
        </Button>
        {excludedCount > 0 ? (
          <p className="text-center text-xs text-zinc-500 dark:text-zinc-400">
            이미 본 {excludedCount}곡은 제외하고 추천해요.
          </p>
        ) : null}
      </div>
    </div>
  );
}

type StatusShellProps = {
  title: string;
  description?: string;
  ctaHref?: string;
  ctaLabel?: string;
};

function StatusShell({
  title,
  description,
  ctaHref,
  ctaLabel,
}: StatusShellProps) {
  return (
    <main className="flex flex-1 flex-col items-center justify-center bg-zinc-50 px-6 py-12 text-center dark:bg-zinc-950">
      <div className="w-full max-w-md flex flex-col items-center gap-4">
        <h1 className="text-xl font-semibold text-zinc-900 dark:text-zinc-50">
          {title}
        </h1>
        {description ? (
          <p className="text-sm text-zinc-600 dark:text-zinc-400">
            {description}
          </p>
        ) : null}
        {ctaHref && ctaLabel ? (
          <Link
            href={ctaHref}
            className="inline-flex h-11 items-center justify-center rounded-full bg-zinc-900 px-5 text-sm font-medium text-white hover:bg-zinc-700 dark:bg-zinc-50 dark:text-zinc-900 dark:hover:bg-zinc-200"
          >
            {ctaLabel}
          </Link>
        ) : null}
      </div>
    </main>
  );
}

function NoSessionFallback() {
  return (
    <StatusShell
      title="음역대가 아직 등록되지 않았습니다"
      description="추천을 받으려면 먼저 음역대를 입력해 주세요."
      ctaHref="/voice-range"
      ctaLabel="음역대 입력하러 가기"
    />
  );
}

type SourceMethodBadgeProps = {
  sourceMethod: VoiceRangeSourceMethod;
};

/**
 * 추천 결과 헤더에 노출하는 음역대 측정 소스 뱃지 (closes #282).
 *
 * "내 음역대가 어떻게 측정된 결과인지" 를 한눈에 보여줘서 사용자가
 * 이 추천 결과를 어떤 입력값과 연결할지 추론할 수 있게 한다.
 *  - MIC_MEASURE → 마이크 측정 (emerald — 정확도 신호)
 *  - OCTAVE_PICK → 직접 선택 (zinc — 중립)
 *  - SELF_REPORT → 자가 보고 (zinc — 중립)
 */
function SourceMethodBadge({ sourceMethod }: SourceMethodBadgeProps) {
  const { label, tone } = describeSourceMethod(sourceMethod);
  return (
    <span
      data-testid="voice-range-source-badge"
      className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${tone}`}
    >
      {label}
    </span>
  );
}

function describeSourceMethod(sourceMethod: VoiceRangeSourceMethod): {
  label: string;
  tone: string;
} {
  switch (sourceMethod) {
    case "MIC_MEASURE":
      return {
        label: "마이크 측정",
        tone:
          "bg-emerald-100 text-emerald-700 dark:bg-emerald-900/50 dark:text-emerald-200",
      };
    case "OCTAVE_PICK":
      return {
        label: "직접 선택",
        tone: "bg-zinc-100 text-zinc-700 dark:bg-zinc-800 dark:text-zinc-300",
      };
    case "SELF_REPORT":
      return {
        label: "자가 보고",
        tone: "bg-zinc-100 text-zinc-700 dark:bg-zinc-800 dark:text-zinc-300",
      };
  }
}
