"use client";

/**
 * P-D 모임 사회자 모드 — "다 같이 즐길 곡" 시퀀스 화면 (이슈 #1601).
 *
 * 기존 /recommend(단일 곡 무한 스크롤)와 별도 경로로, 자리 단계(도입 → 고조 → 마무리)
 * 흐름을 단계별 곡 묶음으로 보여준다(persona-expansion-social-emotional.md §2 P-D / §5-6).
 * 모드 진입은 추천 화면의 CTA 링크로 연결한다(spec §8 Q2 선택지 b — 추천 화면 내 진입을
 * 1차 가설). 이 화면을 거치지 않으면 기존 추천 흐름은 불변(하위호환).
 *
 * be #1599(시퀀스 엔드포인트) 미머지 상태이므로, 시퀀스 호출 실패 시 기존 추천을 단계별로
 * 묶는 client placeholder fallback(lib/sequence.deriveSequenceFallback)으로 병행 동작한다
 * ([[feedback-be-fe-parallel]]).
 */

import { useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";

import { ApiError } from "@/lib/api/client";
import {
  AgeGroup,
  createRecommendation,
  createSequenceRecommendation,
  RecommendationCreateRequest,
  SequenceRecommendationRequest,
  SequenceRecommendationResponse,
} from "@/lib/api/recommendation";
import {
  readVoiceRange,
  VoiceRangeResponse,
} from "@/lib/api/voice-range";
import { deriveSequenceFallback, HOST_PERSONA } from "@/lib/sequence";
import { midiToCombinedNoteName } from "@/lib/notes";
import { VoiceRangeIntuition } from "@/app/voice-range/components/VoiceRangeIntuition";
import { useSessionStore } from "@/store/session";

import { CrowdAgeGroupPicker } from "./components/CrowdAgeGroupPicker";
import { SequenceView } from "./components/SequenceView";

export default function HostRecommendPage() {
  const sessionId = useSessionStore((state) => state.sessionId);

  if (!sessionId) {
    return <NoSessionFallback />;
  }

  return <HostContent sessionId={sessionId} />;
}

type HostContentProps = {
  sessionId: string;
};

/**
 * 시퀀스를 조회한다 — 정식 시퀀스 엔드포인트 우선, 실패 시 기존 추천 기반 client fallback.
 *
 * be #1599 머지 전에는 `createSequenceRecommendation` 이 실패하므로, 일반 추천을 받아
 * 단계별로 묶어 같은 응답 형상으로 돌려준다. BE 가 정식 응답을 내려주면 그 응답을 그대로
 * 쓴다(이후 fallback 미발화).
 */
async function fetchSequence(
  request: SequenceRecommendationRequest,
): Promise<SequenceRecommendationResponse> {
  try {
    return await createSequenceRecommendation(request);
  } catch {
    const fallbackRequest: RecommendationCreateRequest = {
      sessionId: request.sessionId,
      voiceRangeLow: request.voiceRangeLow,
      voiceRangeHigh: request.voiceRangeHigh,
    };
    // 좌중 연령대 중 첫 값을 단일 추천 generationFit 입력으로 흘려보내 picker 가 fallback
    // 에서도 결과에 영향을 준다(정식 엔드포인트는 분포 전체를 받는다).
    if (request.ageGroups && request.ageGroups.length > 0) {
      fallbackRequest.ageGroup = request.ageGroups[0];
    }
    const base = await createRecommendation(fallbackRequest);
    return deriveSequenceFallback(base);
  }
}

function HostContent({ sessionId }: HostContentProps) {
  const voiceRangeQuery = useQuery<VoiceRangeResponse, Error>({
    queryKey: ["voice-range", sessionId],
    queryFn: () => readVoiceRange(sessionId),
  });

  const voiceRangeIdFromStore = useSessionStore((state) => state.voiceRangeId);

  // 좌중 연령대 분포 — 영속하지 않는 화면 로컬 상태. 값이 바뀌면 queryKey 가 바뀌어
  // 시퀀스가 다시 산출된다.
  const [crowdAgeGroups, setCrowdAgeGroups] = useState<AgeGroup[]>([]);

  const isVoiceRangeReady =
    voiceRangeQuery.isSuccess &&
    voiceRangeQuery.data !== undefined &&
    voiceRangeQuery.data.sessionId !== undefined;
  const voiceRangeSessionId = voiceRangeQuery.data?.sessionId;
  const voiceRangeLow = voiceRangeQuery.data?.lowestNoteMidi;
  const voiceRangeHigh = voiceRangeQuery.data?.highestNoteMidi;

  const sequenceQuery = useQuery<SequenceRecommendationResponse, Error>({
    queryKey: [
      "recommendations-sequence",
      sessionId,
      voiceRangeIdFromStore,
      crowdAgeGroups,
    ],
    enabled:
      isVoiceRangeReady &&
      voiceRangeLow !== undefined &&
      voiceRangeHigh !== undefined,
    queryFn: () => {
      const request: SequenceRecommendationRequest = {
        sessionId: voiceRangeSessionId!,
        voiceRangeLow: voiceRangeLow!,
        voiceRangeHigh: voiceRangeHigh!,
        persona: HOST_PERSONA,
      };
      if (crowdAgeGroups.length > 0) {
        request.ageGroups = crowdAgeGroups;
      }
      return fetchSequence(request);
    },
  });

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

  const userRange = {
    lowMidi: voiceRange.lowestNoteMidi,
    highMidi: voiceRange.highestNoteMidi,
  };

  return (
    <main className="flex flex-1 flex-col items-center bg-[var(--bg-subtle)] px-[var(--page-padding-x)] py-[var(--page-padding-y)]">
      <div className="w-full max-w-2xl flex flex-col gap-8">
        <header className="space-y-2">
          <p className="text-xs font-medium uppercase tracking-widest text-[var(--text-caption)]">
            모임 사회자
          </p>
          <h1 className="text-2xl font-semibold text-[var(--text-primary)]">
            다 같이 즐길 곡
          </h1>
          <p className="text-sm text-[var(--text-secondary)]">
            도입 → 고조 → 마무리 흐름으로 자리를 이끌어 드릴게요.
          </p>
          <div className="flex flex-wrap items-center gap-2">
            <p className="text-sm text-[var(--text-secondary)]">
              내 음역대: {midiToCombinedNoteName(voiceRange.lowestNoteMidi)} ~{" "}
              {midiToCombinedNoteName(voiceRange.highestNoteMidi)}
            </p>
          </div>
          <VoiceRangeIntuition
            lowMidi={voiceRange.lowestNoteMidi}
            highMidi={voiceRange.highestNoteMidi}
          />
          <div className="pt-2">
            <Link
              href="/recommend"
              className="text-sm font-medium text-[var(--text-secondary)] underline-offset-4 hover:underline"
            >
              혼자 부를 곡 추천으로 돌아가기
            </Link>
          </div>
        </header>

        <CrowdAgeGroupPicker
          selected={crowdAgeGroups}
          onChange={setCrowdAgeGroups}
        />

        <SequenceFeed query={sequenceQuery} userVoiceRange={userRange} />
      </div>
    </main>
  );
}

type SequenceFeedProps = {
  query: ReturnType<typeof useQuery<SequenceRecommendationResponse, Error>>;
  userVoiceRange: { lowMidi: number; highMidi: number };
};

function SequenceFeed({ query, userVoiceRange }: SequenceFeedProps) {
  const { data, error, isPending, refetch } = query;

  if (isPending) {
    return (
      <p role="status" className="text-sm text-[var(--text-secondary)]">
        시퀀스를 짜는 중...
      </p>
    );
  }

  if (error) {
    return (
      <div className="flex flex-col gap-3 rounded-2xl border border-[var(--danger-border)] bg-[var(--danger-bg)] p-4">
        <p className="text-sm text-[var(--danger-fg-strong)]">
          시퀀스를 불러오지 못했습니다.{" "}
          {error instanceof ApiError
            ? `${error.status}: ${error.message}`
            : error.message}
        </p>
        <button
          type="button"
          onClick={() => refetch()}
          className="inline-flex h-10 w-fit items-center justify-center rounded-full bg-[var(--danger-cta-bg)] px-4 text-sm font-medium text-white hover:bg-[var(--danger-cta-bg-hover)]"
        >
          다시 시도
        </button>
      </div>
    );
  }

  const totalSongs = data.stages.reduce(
    (sum, bundle) => sum + bundle.songs.length,
    0,
  );
  if (totalSongs === 0) {
    return (
      <div
        role="status"
        className="flex flex-col items-start gap-3 rounded-[var(--radius-lg)] border border-[var(--border)] bg-[var(--bg-base)] p-5"
      >
        <p className="text-sm text-[var(--text-secondary)]">
          추천할 수 있는 곡이 없어요. 음역대를 다시 입력해 보세요.
        </p>
        <Link
          href="/voice-range"
          className="inline-flex h-10 items-center justify-center rounded-full bg-[var(--brand-500)] px-4 text-sm font-medium text-white transition-colors duration-[var(--duration-base)] hover:bg-[var(--brand-600)] hover:shadow-[var(--shadow-brand)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--brand-500)] focus-visible:ring-offset-2"
        >
          음역대 다시 입력
        </Link>
      </div>
    );
  }

  return <SequenceView stages={data.stages} userVoiceRange={userVoiceRange} />;
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
    <main className="flex flex-1 flex-col items-center justify-center bg-[var(--bg-subtle)] px-[var(--page-padding-x)] py-[var(--page-padding-y)] text-center">
      <div className="w-full max-w-md flex flex-col items-center gap-4">
        <h1 className="text-xl font-semibold text-[var(--text-primary)]">
          {title}
        </h1>
        {description ? (
          <p className="text-sm text-[var(--text-secondary)]">{description}</p>
        ) : null}
        {ctaHref && ctaLabel ? (
          <Link
            href={ctaHref}
            className="inline-flex h-11 items-center justify-center rounded-full bg-[var(--brand-500)] px-5 text-sm font-medium text-white transition-colors duration-[var(--duration-base)] hover:bg-[var(--brand-600)] hover:shadow-[var(--shadow-brand)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--brand-500)] focus-visible:ring-offset-2"
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
