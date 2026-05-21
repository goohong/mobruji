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
 */

import { useEffect } from "react";
import Link from "next/link";
import { useMutation, useQuery } from "@tanstack/react-query";

import { ApiError } from "@/lib/api/client";
import {
  createRecommendation,
  RecommendationResponse,
} from "@/lib/api/recommendation";
import { readVoiceRange, VoiceRangeResponse } from "@/lib/api/voice-range";
import { midiToNoteName } from "@/lib/notes";
import { useSessionStore } from "@/store/session";

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
  };

  const recommendationMutation = useMutation<
    RecommendationResponse,
    Error,
    RecommendationInput
  >({
    mutationFn: (input) => createRecommendation(input),
  });

  // 음역대 조회 성공 시 자동으로 추천 호출 (1회).
  // 객체 의존성으로 인한 useEffect 무한 재실행 함정을 피하기 위해
  // primitive 필드와 mutation의 stable 함수 ref만 의존성에 둔다.
  // (TanStack Query는 .mutate 함수 ref는 안정적으로 보장한다.)
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
          <p className="text-sm text-zinc-600 dark:text-zinc-400">
            내 음역대: {midiToNoteName(voiceRange.lowestNoteMidi)} ~{" "}
            {midiToNoteName(voiceRange.highestNoteMidi)}
          </p>
          <div className="pt-2">
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
          onRetry={() =>
            recommendationMutation.mutate({
              sessionId: voiceRange.sessionId,
              voiceRangeLow: voiceRange.lowestNoteMidi,
              voiceRangeHigh: voiceRange.highestNoteMidi,
            })
          }
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
};

function RecommendationList({
  isPending,
  error,
  data,
  onRetry,
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
        <button
          type="button"
          onClick={onRetry}
          className="self-start rounded-full bg-red-600 px-4 py-2 text-sm font-medium text-white hover:bg-red-700"
        >
          다시 시도
        </button>
      </div>
    );
  }

  if (!data || data.recommendations.length === 0) {
    return (
      <div className="flex flex-col items-start gap-3 rounded-2xl border border-zinc-200 bg-white p-5 dark:border-zinc-800 dark:bg-zinc-900">
        <p className="text-sm text-zinc-700 dark:text-zinc-300">
          조건에 맞는 곡이 없습니다. 음역대를 다시 확인해 주세요.
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

  return (
    <ul className="flex flex-col gap-3">
      {data.recommendations.map((item) => (
        <SongCard key={item.song.id} item={item} />
      ))}
    </ul>
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
