"use client";

/**
 * 추천 히스토리 페이지 (closes #134).
 *
 * - zustand persist(`useHistoryStore`)에 누적된 추천 응답들을 시간 역순 카드 리스트로 노출.
 * - 각 카드:
 *   - 받은 시간(상대 시간) + 추천 곡 미리보기(최대 3건) + "+N개 더보기"
 *   - "이 추천 다시 보기" — 결과 곡 리스트 전체 펼치기 (페이지 내 inline expand)
 *   - "삭제" — 단일 항목 제거
 * - 빈 상태: 음역대 입력 CTA + 안내 문구.
 * - 하단 "전체 삭제" 버튼 — confirm prompt 후 clearHistory.
 *
 * 결정 배경:
 *  - "다시 보기"를 별도 URL(`/recommend?session=...`)로 보내지 않고 페이지 내 expand 로 둔다.
 *    추천 결과는 BE 가 결정성을 보장하므로 재호출이 무료이긴 하지만, 히스토리는 BE 호출 없이
 *    클라이언트 본인 상태만으로 보여주는 것이 v1 가치 — 네트워크/세션 의존성 없이 동작.
 *  - confirm() 호출은 happy-dom 환경에서도 stub 가능하고, UX 마찰을 늘려 실수 삭제를 막는다.
 */

import { useState } from "react";
import Link from "next/link";

import { SongCard } from "@/app/recommend/components/SongCard";
import { formatRelativeKorean } from "@/lib/relativeTime";
import { extractVoiceRangeProgress } from "@/lib/voiceRangeProgress";
import {
  useHistoryStore,
  type RecommendationHistoryEntry,
} from "@/store/history";

import { VoiceRangeProgressCard } from "./components/VoiceRangeProgressCard";

const PREVIEW_COUNT = 3;

export default function HistoryPage() {
  const recommendations = useHistoryStore((state) => state.recommendations);
  const removeRecommendation = useHistoryStore(
    (state) => state.removeRecommendation,
  );
  const clearHistory = useHistoryStore((state) => state.clearHistory);

  if (recommendations.length === 0) {
    return <EmptyHistory />;
  }

  const handleClear = () => {
    // confirm 은 사용자 마찰을 한 단계 추가 — 잘못된 클릭으로 전체가 날아가는 사고 방지.
    if (
      typeof window !== "undefined" &&
      window.confirm("히스토리 전체를 삭제할까요? 되돌릴 수 없습니다.")
    ) {
      clearHistory();
    }
  };

  // 음역 발전 추적(closes #170) — entries 가 2건 이상이고 MIDI 스냅샷이 있는 경우만 표시.
  // 1건 이하인 경우는 카드 자리에 "더 측정해보세요" CTA 를 노출 (사용자 동기부여).
  const progressSummary = extractVoiceRangeProgress(recommendations);
  const measuredCount = recommendations.filter(
    (entry) =>
      typeof entry.voiceRangeLowMidi === "number" &&
      typeof entry.voiceRangeHighMidi === "number",
  ).length;

  return (
    <main className="flex flex-1 flex-col items-center bg-zinc-50 px-6 py-12 dark:bg-zinc-950">
      <div className="w-full max-w-2xl flex flex-col gap-6">
        <header className="space-y-2">
          <p className="text-xs font-medium uppercase tracking-widest text-zinc-500 dark:text-zinc-400">
            History
          </p>
          <h1 className="text-2xl font-semibold text-zinc-900 dark:text-zinc-50">
            받은 추천 다시 보기
          </h1>
          <p className="text-sm text-zinc-600 dark:text-zinc-400">
            최근 {recommendations.length}건의 추천을 기록해두었어요. 최대 20건까지 보관됩니다.
          </p>
        </header>

        {progressSummary ? (
          <VoiceRangeProgressCard summary={progressSummary} />
        ) : measuredCount <= 1 ? (
          <ProgressEmptyCta />
        ) : null}

        <ul aria-label="추천 히스토리" className="flex flex-col gap-3">
          {recommendations.map((entry) => (
            <HistoryCard
              key={entry.id}
              entry={entry}
              onRemove={() => removeRecommendation(entry.id)}
            />
          ))}
        </ul>

        <div className="flex justify-end pt-2">
          <button
            type="button"
            onClick={handleClear}
            className="rounded-full border border-zinc-300 px-4 py-2 text-xs font-medium text-zinc-700 transition-colors hover:bg-zinc-100 dark:border-zinc-700 dark:text-zinc-300 dark:hover:bg-zinc-900"
          >
            전체 삭제
          </button>
        </div>
      </div>
    </main>
  );
}

type HistoryCardProps = {
  entry: RecommendationHistoryEntry;
  onRemove: () => void;
};

function HistoryCard({ entry, onRemove }: HistoryCardProps) {
  const [expanded, setExpanded] = useState(false);
  const relativeTime = formatRelativeKorean(entry.requestedAt);
  const visibleSongs = expanded
    ? entry.songs
    : entry.songs.slice(0, PREVIEW_COUNT);
  const remaining = entry.songs.length - PREVIEW_COUNT;

  return (
    <li className="flex flex-col gap-3 rounded-2xl bg-white p-4 ring-1 ring-zinc-200 dark:bg-zinc-900 dark:ring-zinc-800">
      <div className="flex items-start justify-between gap-3">
        <div className="flex flex-col gap-0.5">
          <time
            dateTime={entry.requestedAt}
            className="text-sm font-medium text-zinc-800 dark:text-zinc-200"
          >
            {relativeTime}
          </time>
          <p className="text-xs text-zinc-500 dark:text-zinc-400">
            추천 {entry.songs.length}곡
            {entry.excludedSongIds.length > 0
              ? ` · ${entry.excludedSongIds.length}곡 제외`
              : ""}
          </p>
        </div>
        <button
          type="button"
          onClick={onRemove}
          aria-label={`${relativeTime} 추천 삭제`}
          className="rounded-full border border-transparent px-2 py-1 text-xs font-medium text-zinc-500 transition-colors hover:border-zinc-300 hover:text-zinc-800 dark:text-zinc-400 dark:hover:border-zinc-700 dark:hover:text-zinc-100"
        >
          삭제
        </button>
      </div>

      <ul aria-label="추천 곡 미리보기" className="flex flex-col gap-2">
        {visibleSongs.map((item) => (
          <SongCard
            key={item.song.id}
            item={item}
            href={`/songs/${item.song.id}`}
          />
        ))}
      </ul>

      {remaining > 0 ? (
        <button
          type="button"
          onClick={() => setExpanded((prev) => !prev)}
          aria-expanded={expanded}
          className="self-start text-sm font-medium text-zinc-700 underline-offset-4 hover:underline dark:text-zinc-300"
        >
          {expanded ? "접기" : `이 추천 다시 보기 (+${remaining}개 더보기)`}
        </button>
      ) : (
        <p className="text-xs text-zinc-500 dark:text-zinc-400">
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
      className="flex flex-col gap-3 rounded-2xl border border-dashed border-zinc-300 bg-white p-4 dark:border-zinc-700 dark:bg-zinc-900"
    >
      <h2
        id="voice-range-progress-empty-heading"
        className="text-base font-semibold text-zinc-900 dark:text-zinc-50"
      >
        음역 발전 그래프는 측정 2번부터
      </h2>
      <p className="text-sm text-zinc-600 dark:text-zinc-400">
        같은 음역으로 두 번 이상 추천을 받으면 측정값의 변화를 그래프로 보여드릴게요.
        자동 측정을 한 번 더 시도해보세요.
      </p>
      <Link
        href="/voice-range/auto"
        className="inline-flex h-10 w-fit items-center justify-center rounded-full bg-zinc-900 px-4 text-sm font-medium text-white hover:bg-zinc-700 dark:bg-zinc-50 dark:text-zinc-900 dark:hover:bg-zinc-200"
      >
        더 측정해보기
      </Link>
    </section>
  );
}

function EmptyHistory() {
  return (
    <main className="flex flex-1 flex-col items-center justify-center bg-zinc-50 px-6 py-12 text-center dark:bg-zinc-950">
      <div className="w-full max-w-md flex flex-col items-center gap-4">
        <h1 className="text-xl font-semibold text-zinc-900 dark:text-zinc-50">
          아직 받은 추천이 없어요
        </h1>
        <p className="text-sm text-zinc-600 dark:text-zinc-400">
          음역대를 입력하고 추천을 한 번 받아보세요. 받은 추천이 여기에 기록됩니다.
        </p>
        <Link
          href="/voice-range"
          className="inline-flex h-11 items-center justify-center rounded-full bg-zinc-900 px-5 text-sm font-medium text-white hover:bg-zinc-700 dark:bg-zinc-50 dark:text-zinc-900 dark:hover:bg-zinc-200"
        >
          음역대 입력하러 가기
        </Link>
      </div>
    </main>
  );
}
