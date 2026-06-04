"use client";

/**
 * 추천 히스토리 페이지 (closes #134, BE source-of-truth 전환 closes #263).
 *
 * 데이터 소스 우선순위 (spec recommendation-history-and-feedback §5-6 — PR D):
 *   1. BE `/api/v1/sessions/{sid}/recommendation-history` 응답이 1건 이상 → BE 사용.
 *   2. BE 응답이 비었거나 호출 실패(sessionId 없음/네트워크 에러) → zustand persist 사용.
 * 결정 배경: 다른 기기/브라우저에서 같은 sessionId 로 접속한 사용자도 동일 히스토리를
 * 보여주기 위함(spec §1, §2). 단 BE 가 죽거나 sessionId 가 hydration 전이라도 페이지가
 * 깨지지 않도록 localStorage fallback 을 유지한다 (voice-range-progress 와 동일 패턴).
 *
 * 각 카드:
 *   - 받은 시간(상대 시간) + 추천 곡 미리보기(최대 3건) + "+N개 더보기"
 *   - "이 추천 다시 보기" — 결과 곡 리스트 전체 펼치기 (페이지 내 inline expand)
 *   - "삭제" — localStorage 한정. BE entry 는 mutation API 미구현(PR F 대기) 이라 비활성.
 * 빈 상태: 음역대 입력 CTA + 안내 문구.
 * 하단 "전체 삭제" 버튼 — confirm prompt 후 localStorage 만 clearHistory.
 *
 * BE 응답에는 mood/preferredBpm 메타가 있으므로 카드 부제목에 가볍게 노출한다.
 * (localStorage entry 에는 해당 메타가 없어 null 일 때만 표시.)
 */

import { useMemo, useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";

import { SongCard } from "@/app/recommend/components/SongCard";
import { formatRelativeKorean } from "@/lib/relativeTime";
import {
  extractVoiceRangeProgress,
  extractVoiceRangeProgressFromSnapshots,
  type VoiceRangeProgressSummary,
} from "@/lib/voiceRangeProgress";
import {
  readVoiceRangeHistory,
  type VoiceRangeHistoryResponse,
} from "@/lib/api/voiceRangeHistory";
import {
  readRecommendationHistory,
  type RecommendationHistoryEntryResponse,
  type RecommendationHistoryListResponse,
} from "@/lib/api/recommendationHistory";
import {
  useHistoryStore,
  type RecommendationHistoryEntry,
} from "@/store/history";
import { useSessionStore } from "@/store/session";

import { VoiceRangeProgressCard } from "./components/VoiceRangeProgressCard";

const PREVIEW_COUNT = 3;

/**
 * 화면 렌더용 통합 entry. localStorage entry 와 BE entry 둘 다 이 shape 으로
 * 정규화해 단일 카드 컴포넌트에서 다룬다.
 *
 * - `source: "backend"` 인 entry 는 BE 가 source-of-truth — 삭제 액션 비활성.
 * - `source: "local"` 인 entry 는 localStorage — store.removeRecommendation 호출 가능.
 */
type DisplayEntry = {
  id: string;
  source: "backend" | "local";
  requestedAt: string;
  songs: RecommendationHistoryEntry["songs"];
  excludedSongIds: number[];
  /** BE entry 한정 — 카드 부제목 라벨에 노출. localStorage entry 는 항상 null. */
  mood: string | null;
  preferredBpm: number | null;
};

export default function HistoryPage() {
  const localRecommendations = useHistoryStore(
    (state) => state.recommendations,
  );
  const removeRecommendation = useHistoryStore(
    (state) => state.removeRecommendation,
  );
  const clearHistory = useHistoryStore((state) => state.clearHistory);
  // sessionId 는 zustand persist 로 hydration 후에야 truthy 가 된다. 없는 동안엔
  // BE 호출을 건너뛰고 localStorage(useHistoryStore) 만으로 렌더한다.
  const sessionId = useSessionStore((state) => state.sessionId);

  // spec voice-range-progress §3 — BE 음역 시계열을 source-of-truth 로 사용한다.
  // sessionId 가 없거나 호출이 실패하면 localStorage 기반(extractVoiceRangeProgress)
  // 으로 graceful fallback 한다(spec §3: "LocalStorage 는 오프라인 fallback").
  const voiceRangeHistoryQuery = useQuery<VoiceRangeHistoryResponse, Error>({
    queryKey: ["voice-range-history", sessionId],
    queryFn: ({ signal }) => readVoiceRangeHistory(sessionId!, signal),
    enabled: Boolean(sessionId),
    // 시계열은 자주 갱신될 필요가 없다(사용자 액션 한 번 = snapshot 한 건).
    // 캐시는 React Query 기본 5분 유지 — 같은 페이지 재방문 시 즉시 표시.
    retry: 1,
  });

  // spec recommendation-history-and-feedback §5-6 (PR D) — BE 히스토리를 우선 사용.
  // BE 호출 실패(401/네트워크) 시 localStorage 로 graceful fallback. retry: 1 로 일시
  // 네트워크 흔들림에는 한 번 더 시도하되 무한 재시도하지 않는다.
  const recommendationHistoryQuery = useQuery<
    RecommendationHistoryListResponse,
    Error
  >({
    queryKey: ["recommendation-history", sessionId],
    queryFn: ({ signal }) => readRecommendationHistory(sessionId!, signal),
    enabled: Boolean(sessionId),
    retry: 1,
  });

  // BE 응답이 있고 1건 이상이면 BE 데이터, 아니면 localStorage. useMemo 로 정규화
  // 비용은 무시 가능 수준이지만 자식 렌더가 같은 reference 를 보도록 유지한다.
  const displayEntries = useMemo<DisplayEntry[]>(() => {
    const backendEntries =
      recommendationHistoryQuery.data?.recommendationHistoryResponses ?? null;
    if (backendEntries && backendEntries.length > 0) {
      return backendEntries.map(toDisplayEntryFromBackend);
    }
    return localRecommendations.map(toDisplayEntryFromLocal);
  }, [
    recommendationHistoryQuery.data?.recommendationHistoryResponses,
    localRecommendations,
  ]);

  // 빈 상태 판정: BE/localStorage 둘 다 비어 있을 때만. BE 가 아직 로딩 중이라면
  // localStorage 라도 있으면 그것을 보여주고, 둘 다 없으면 빈 상태.
  if (displayEntries.length === 0) {
    return <EmptyHistory />;
  }

  // BE source 여부에 따라 "전체 삭제" 버튼의 라벨/확인 문구를 분기하기 위해
  // handleClear 보다 먼저 계산한다(아래 progressSummary 블록과 동일 변수도 별도 산출).
  // BE entry 는 mutation API 미구현(PR F 대기)이라 localStorage 만 비우면 다음
  // 페이지 진입 시 다시 보임 — 사용자 혼란 방지를 위해 라벨을 "이 기기 캐시 비우기"
  // 로 분기하고 confirm 문구에 그 사실을 명시한다 (#295 항목 3 후속).
  const hasBackendEntries =
    (recommendationHistoryQuery.data?.recommendationHistoryResponses?.length ??
      0) > 0;
  const clearButtonLabel = hasBackendEntries
    ? "이 기기 캐시 비우기"
    : "전체 삭제";
  const clearConfirmMessage = hasBackendEntries
    ? "이 기기에 저장된 히스토리만 지웁니다. 서버에 저장된 추천은 다음 방문 시 다시 보입니다. 계속할까요?"
    : "히스토리 전체를 삭제할까요? 되돌릴 수 없습니다.";

  const handleClear = () => {
    // confirm 은 사용자 마찰을 한 단계 추가 — 잘못된 클릭으로 전체가 날아가는 사고 방지.
    // 본 PR 범위에서는 BE entry 삭제 API 가 없으므로 localStorage 만 정리한다.
    // BE 데이터는 다음 페이지 진입 시 다시 fetch 되어 그대로 보인다 — 사용자 기대치와 갭이 있을 수 있어
    // (#295 항목 3 후속) BE source 일 때는 confirm 문구에 명시한다.
    // spec §4 (out of scope) 에 좋아요/북마크/추천 결과 삭제는 빠져있고, "history 페이지에서 BE 데이터 영구 삭제"
    // 도 spec 에 없으므로 향후 별도 spec/API 가 들어오기 전까지는 "내 브라우저 캐시 비우기" 의미로 둔다.
    if (typeof window !== "undefined" && window.confirm(clearConfirmMessage)) {
      clearHistory();
    }
  };

  // 음역 발전 추적(closes #170) — entries 가 2건 이상이고 MIDI 스냅샷이 있는 경우만 표시.
  // 1건 이하인 경우는 카드 자리에 "더 측정해보세요" CTA 를 노출 (사용자 동기부여).
  // pickProgressSummary 는 localStorage entries 기반 fallback 만 처리 — voice-range 별도 BE source 가 우선.
  const progressSummary = pickProgressSummary({
    snapshots: voiceRangeHistoryQuery.data?.voiceRangeSnapshotResponses ?? null,
    isBackendError: voiceRangeHistoryQuery.isError,
    recommendations: localRecommendations,
  });
  const measuredCount = countMeasuredEntries(
    voiceRangeHistoryQuery.data?.voiceRangeSnapshotResponses ?? null,
    localRecommendations,
  );

  // 헤더 카피와 일치시키기 위한 alias — 이미 위에서 hasBackendEntries 로 계산했다.
  const isBackendSource = hasBackendEntries;

  /*
   * ADR-0018 단계 4 PR 3 — /history 페이지 토큰 swap (homepage PR #1137 패턴).
   *
   * swap 한 요소 (first-paint 핵심):
   *  1) <main> 배경 + padding : bg-zinc-50 dark:bg-zinc-950, px-6 py-12 → tokens
   *  2) h1 (받은 추천 다시 보기) : text-zinc-900 dark:text-zinc-50 → --text-primary
   *  3) HistoryCard ring/bg/radius : bg-white ring-zinc-200 dark:bg-zinc-900 dark:ring-zinc-800
   *     rounded-2xl → tokens
   *  4) ProgressEmptyCta : bg-white border-zinc-300 dark:bg-zinc-900 dark:border-zinc-700
   *     rounded-2xl + CTA bg-zinc-900 → tokens + brand-500/600
   *  5) EmptyHistory : bg-zinc-50 dark:bg-zinc-950, h1 + CTA → tokens
   *
   * 잔존 swap (sub-PR 5 — 매트릭스 PR #1263):
   *  - count live 영역 부제 → --text-secondary (likes/bookmarks 동일 패턴)
   *  - 전체 삭제 button (border/text/hover) → border/--text-label + hover:bg-muted
   *  - HistoryCard time → --text-body-emphasis
   *  - HistoryCard 삭제 button hover → border/--text-primary
   *  - HistoryCard 더보기 button → --text-label
   *  본 sub-PR 로 /history 의 zinc-* className 잔존 0 달성.
   */
  return (
    <main className="flex flex-1 flex-col items-center bg-[var(--bg-subtle)] px-[var(--page-padding-x)] py-[var(--page-padding-y)]">
      <div className="w-full max-w-2xl flex flex-col gap-6">
        <header className="space-y-2">
          <p className="text-xs font-medium uppercase tracking-widest text-[var(--text-caption)]">
            기록
          </p>
          <h1 className="text-2xl font-semibold text-[var(--text-primary)]">
            받은 추천 다시 보기
          </h1>
          {/*
            (closes #435) 카운트 영역을 스크린 리더 라이브 영역으로 마킹한다.
            BE 응답 도착(source 분기 + 건수 변화) 또는 localStorage entry 변경 시
            메시지가 바뀌므로 polite live 로 알린다. PR #428/#433 와 동일 패턴 —
            /recommend, /likes, /bookmarks 와 일관성 확보.
            시각 표시는 그대로 유지하고 `aria-live` 만 부여 — 별도 sr-only 영역을
            중복으로 두면 시각/SR 텍스트가 어긋날 위험이 있어 헤더 카피에 직접 부여.
          */}
          <p
            data-testid="history-count-live"
            role="status"
            aria-live="polite"
            aria-atomic="true"
            className="text-sm text-[var(--text-secondary)]"
          >
            {isBackendSource
              ? `세션 ID 기준 ${displayEntries.length}건의 추천을 서버에서 불러왔어요.`
              : `최근 ${displayEntries.length}건의 추천을 기록해두었어요. 최대 20건까지 보관됩니다.`}
          </p>
        </header>

        {progressSummary ? (
          <VoiceRangeProgressCard summary={progressSummary} />
        ) : measuredCount <= 1 ? (
          <ProgressEmptyCta />
        ) : null}

        <ul aria-label="추천 히스토리" className="flex flex-col gap-3">
          {displayEntries.map((entry) => (
            <HistoryCard
              key={entry.id}
              entry={entry}
              onRemove={
                entry.source === "local"
                  ? () => removeRecommendation(entry.id)
                  : undefined
              }
            />
          ))}
        </ul>

        <div className="flex justify-end pt-2">
          <button
            type="button"
            onClick={handleClear}
            className="rounded-full border border-[var(--border)] px-4 py-2 text-xs font-medium text-[var(--text-label)] transition-colors hover:bg-[var(--bg-muted)]"
          >
            {clearButtonLabel}
          </button>
        </div>
      </div>
    </main>
  );
}

/**
 * BE entry 의 mood enum 을 한국어 라벨로 매핑한다. 추후 곡 카드의 mood 표시와
 * 통일할 여지가 있으나 본 PR 범위는 history 카드 부제목 한정 — 별도 헬퍼 분리는
 * 차후 (현재 6개뿐이라 dict 1개로 충분).
 */
const MOOD_LABELS: Record<string, string> = {
  UPBEAT: "신나는",
  CALM: "잔잔한",
  EMOTIONAL: "감성적인",
  POWERFUL: "파워풀한",
  GROOVY: "그루비한",
  NOSTALGIC: "추억의",
};

function toDisplayEntryFromBackend(
  response: RecommendationHistoryEntryResponse,
): DisplayEntry {
  return {
    // BE requestId 는 같은 세션 안에서 unique — React key 로 안전.
    id: `be-${response.requestId}`,
    source: "backend",
    requestedAt: response.requestedAt,
    songs: response.recommendations,
    excludedSongIds: [],
    mood: response.mood ? (MOOD_LABELS[response.mood] ?? response.mood) : null,
    preferredBpm: response.preferredBpm,
  };
}

function toDisplayEntryFromLocal(
  entry: RecommendationHistoryEntry,
): DisplayEntry {
  return {
    id: entry.id,
    source: "local",
    requestedAt: entry.requestedAt,
    songs: entry.songs,
    excludedSongIds: entry.excludedSongIds,
    mood: null,
    preferredBpm: null,
  };
}

type PickProgressSummaryArgs = {
  snapshots: ReadonlyArray<
    VoiceRangeHistoryResponse["voiceRangeSnapshotResponses"][number]
  > | null;
  isBackendError: boolean;
  recommendations: readonly RecommendationHistoryEntry[];
};

/**
 * source-of-truth 우선순위 결정:
 *   1. BE 시계열 응답이 있고 datapoint ≥ 2 → BE summary 사용 (spec §3).
 *   2. BE 호출이 에러거나 응답이 empty/1건 → localStorage fallback (spec §3 fallback).
 * 결과가 null 이면 호출 측이 "측정 더 해보세요" CTA / 카드 미노출로 분기한다.
 */
function pickProgressSummary({
  snapshots,
  isBackendError,
  recommendations,
}: PickProgressSummaryArgs): VoiceRangeProgressSummary | null {
  if (snapshots && snapshots.length >= 2) {
    const summary = extractVoiceRangeProgressFromSnapshots(snapshots);
    if (summary) {
      return summary;
    }
  }
  // BE 응답이 있긴 하지만 1건 이하 → 신뢰값이므로 fallback 하지 않고 그대로 null.
  // 단, BE 호출 자체가 실패한 경우엔 localStorage 로 graceful fallback.
  if (snapshots && !isBackendError) {
    return null;
  }
  return extractVoiceRangeProgress(recommendations);
}

/**
 * "더 측정해보세요" CTA 표시 여부 판정에 쓰일 측정 횟수.
 * BE 응답이 있으면 그 길이를, 없으면 localStorage entry 중 MIDI 가진 것 수를 센다.
 */
function countMeasuredEntries(
  snapshots: ReadonlyArray<
    VoiceRangeHistoryResponse["voiceRangeSnapshotResponses"][number]
  > | null,
  recommendations: readonly RecommendationHistoryEntry[],
): number {
  if (snapshots) {
    return snapshots.length;
  }
  return recommendations.filter(
    (entry) =>
      typeof entry.voiceRangeLowMidi === "number" &&
      typeof entry.voiceRangeHighMidi === "number",
  ).length;
}

type HistoryCardProps = {
  entry: DisplayEntry;
  /**
   * 삭제 콜백. `undefined` 이면 삭제 버튼을 렌더하지 않는다 (BE entry —
   * 서버 측 삭제 API 미구현, PR F 대기). 사용자가 헷갈리지 않도록 버튼 자체를 숨긴다.
   */
  onRemove: (() => void) | undefined;
};

function HistoryCard({ entry, onRemove }: HistoryCardProps) {
  const [expanded, setExpanded] = useState(false);
  const relativeTime = formatRelativeKorean(entry.requestedAt);
  const visibleSongs = expanded
    ? entry.songs
    : entry.songs.slice(0, PREVIEW_COUNT);
  const remaining = entry.songs.length - PREVIEW_COUNT;
  // BE entry 한정 메타: "신나는 · 120 BPM" 형태로 한 줄에 노출.
  const metaParts: string[] = [];
  if (entry.mood) metaParts.push(entry.mood);
  if (typeof entry.preferredBpm === "number")
    metaParts.push(`${entry.preferredBpm} BPM`);

  return (
    <li className="flex flex-col gap-3 rounded-[var(--radius-lg)] bg-[var(--bg-base)] p-4 ring-1 ring-[var(--border)]">
      <div className="flex items-start justify-between gap-3">
        <div className="flex flex-col gap-0.5">
          <time
            dateTime={entry.requestedAt}
            className="text-sm font-medium text-[var(--text-body-emphasis)]"
          >
            {relativeTime}
          </time>
          <p className="text-xs text-[var(--text-caption)]">
            추천 {entry.songs.length}곡
            {entry.excludedSongIds.length > 0
              ? ` · ${entry.excludedSongIds.length}곡 제외`
              : ""}
            {metaParts.length > 0 ? ` · ${metaParts.join(" · ")}` : ""}
          </p>
        </div>
        {onRemove ? (
          <button
            type="button"
            onClick={onRemove}
            aria-label={`${relativeTime} 추천 삭제`}
            className="rounded-full border border-transparent px-2 py-1 text-xs font-medium text-[var(--text-caption)] transition-colors hover:border-[var(--border)] hover:text-[var(--text-primary)]"
          >
            삭제
          </button>
        ) : null}
      </div>

      <ul aria-label="추천 곡 미리보기" className="flex flex-col gap-2">
        {visibleSongs.map((item, index) => (
          <SongCard
            key={item.song.id}
            item={item}
            index={index}
            href={`/songs/${item.song.id}`}
          />
        ))}
      </ul>

      {remaining > 0 ? (
        <button
          type="button"
          onClick={() => setExpanded((prev) => !prev)}
          aria-expanded={expanded}
          className="self-start text-sm font-medium text-[var(--text-label)] underline-offset-4 hover:underline"
        >
          {expanded ? "접기" : `이 추천 다시 보기 (+${remaining}개 더보기)`}
        </button>
      ) : (
        <p className="text-xs text-[var(--text-caption)]">
          전체 {entry.songs.length}곡 보기 중
        </p>
      )}
    </li>
  );
}

function ProgressEmptyCta() {
  // 음역 발전 추적은 datapoint 2개 이상 필요 — 1건 이하인 경우 동기부여 CTA.
  // 빈 상태 페이지(`EmptyHistory`)와 다른 점: 여기는 이미 추천 1건은 받았지만 음역 측정이
  // 부족한 케이스. 사용자가 다른 측정 방식(자동 마이크 등)으로 한 번 더 측정하도록 유도.
  return (
    <section
      aria-labelledby="voice-range-progress-empty-heading"
      className="flex flex-col gap-3 rounded-[var(--radius-lg)] border border-dashed border-[var(--border)] bg-[var(--bg-base)] p-4"
    >
      <h2
        id="voice-range-progress-empty-heading"
        className="text-base font-semibold text-[var(--text-primary)]"
      >
        음역 발전 그래프는 측정 2번부터
      </h2>
      <p className="text-sm text-[var(--text-secondary)]">
        같은 음역으로 두 번 이상 추천을 받으면 측정값의 변화를 그래프로 보여드릴게요.
        자동 측정을 한 번 더 시도해보세요.
      </p>
      <Link
        href="/voice-range/auto"
        className="inline-flex h-10 w-fit items-center justify-center rounded-full bg-[var(--brand-500)] px-4 text-sm font-medium text-white transition-colors duration-[var(--duration-base)] hover:bg-[var(--brand-600)] hover:shadow-[var(--shadow-brand)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--brand-500)] focus-visible:ring-offset-2"
      >
        더 측정해보기
      </Link>
    </section>
  );
}

function EmptyHistory() {
  return (
    <main className="flex flex-1 flex-col items-center justify-center bg-[var(--bg-subtle)] px-[var(--page-padding-x)] py-[var(--page-padding-y)] text-center">
      <div className="w-full max-w-md flex flex-col items-center gap-4">
        <h1 className="text-xl font-semibold text-[var(--text-primary)]">
          아직 받은 추천이 없어요
        </h1>
        <p className="text-sm text-[var(--text-secondary)]">
          음역대를 입력하고 추천을 한 번 받아보세요. 받은 추천이 여기에 기록됩니다.
        </p>
        <Link
          href="/voice-range"
          className="inline-flex h-11 items-center justify-center rounded-full bg-[var(--brand-500)] px-5 text-sm font-medium text-white transition-colors duration-[var(--duration-base)] hover:bg-[var(--brand-600)] hover:shadow-[var(--shadow-brand)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--brand-500)] focus-visible:ring-offset-2"
        >
          음역대 입력하러 가기
        </Link>
      </div>
    </main>
  );
}
