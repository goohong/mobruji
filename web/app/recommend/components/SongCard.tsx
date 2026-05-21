/**
 * 곡 카드 컴포넌트.
 *
 * 이슈 #75 / PR #76: 추천 결과 페이지에서 곡 1개를 표현. 음역대 막대 그래프 대신
 * **가창 난이도 라벨 + 최고음**을 핵심 정보로 노출한다.
 *
 * 이슈 #91 #92 / PR #93: 곡 검색 페이지(`/songs`)에서도 동일한 카드 룩앤필을 사용한다.
 * 추천 컨텍스트(`item`)와 검색 컨텍스트(`song`) 둘 다 지원하도록 props가 분기된다.
 *
 * 이슈 #100 / PR #101: `href` prop을 지원해 카드 전체를 곡 상세 페이지로 가는
 * 링크로 만든다. href가 주어지면 카드 표면 전체가 `next/link`의 `<Link>`로 감싸지며,
 * 키보드 포커스/엔터/스페이스 활성화는 next/link의 기본 동작을 사용한다.
 *
 * 표시 정보:
 *   - rank position (#1, #2 ...) — 추천 컨텍스트에서만
 *   - 제목 (큰 글씨)
 *   - 아티스트 (작게)
 *   - 가창 난이도 라벨 (EASY/NORMAL/HARD)
 *     · `song.difficulty`가 있으면 그 값을, 없으면 `deriveDifficulty(lowMidi, highMidi)`로 계산.
 *     · 둘 다 없으면(legacy 응답) 라벨을 숨긴다.
 *   - 최고음 음표명 (예: F#5) — `midiToNoteName(highMidi)`
 *   - 최저음 음표명 (작게, 부가)
 *   - 장르 칩 (있으면)
 *   - matchReason 한 줄 — 추천 컨텍스트에서만
 *   - 키(키 원본) 라벨
 *   - score — 추천 컨텍스트에서만
 *
 * 호버/포커스 상태는 ring/shadow 변화로 표현. 모바일 우선.
 */

"use client";

import Link from "next/link";
import type { ReactNode } from "react";

import type {
  RecommendedSongResponse,
  SongResponse,
} from "@/lib/api/recommendation";
import {
  deriveDifficulty,
  difficultyLabel,
  type Difficulty,
} from "@/lib/difficulty";
import { midiToNoteName } from "@/lib/notes";

/**
 * Props 분기:
 *   - `item: RecommendedSongResponse` — 추천 결과 카드. rank/score/matchReason 노출.
 *   - `song: SongResponse` — 검색 결과 카드. 추천 컨텍스트 필드는 모두 숨김.
 *
 * 두 모드 모두 동일한 시각 표현(난이도/최고음/장르/키)을 공유하며, 옵셔널 `href`로
 * 카드 전체를 클릭 가능한 링크로 만들 수 있다.
 */
type SongCardProps =
  | { item: RecommendedSongResponse; song?: never; href?: string }
  | { song: SongResponse; item?: never; href?: string };

export function SongCard(props: SongCardProps) {
  const song: SongResponse = "item" in props && props.item ? props.item.song : props.song!;
  const item: RecommendedSongResponse | null =
    "item" in props && props.item ? props.item : null;
  const href: string | undefined = props.href;
  const keyLabel = formatMusicalKey(song.keyOriginal);
  const difficulty = resolveDifficulty(song);
  const highestNoteName =
    typeof song.highMidi === "number" ? midiToNoteName(song.highMidi) : null;
  const lowestNoteName =
    typeof song.lowMidi === "number" ? midiToNoteName(song.lowMidi) : null;

  const body: ReactNode = (
    <>
      <div className="flex items-start justify-between gap-4">
        <div className="flex min-w-0 flex-col gap-1">
          {item ? (
            <p className="text-xs font-medium text-zinc-500 dark:text-zinc-400">
              #{item.rankPosition}
            </p>
          ) : null}
          <h2 className="truncate text-lg font-semibold text-zinc-900 dark:text-zinc-50">
            {song.title}
          </h2>
          <p className="truncate text-sm text-zinc-600 dark:text-zinc-400">
            {song.artist}
          </p>
        </div>
        <div className="flex flex-col items-end gap-1 text-right">
          {difficulty ? <DifficultyBadge difficulty={difficulty} /> : null}
          <span className="text-xs text-zinc-500 dark:text-zinc-400">키</span>
          <span className="text-sm font-medium text-zinc-800 dark:text-zinc-200">
            {keyLabel}
          </span>
        </div>
      </div>

      {(highestNoteName || lowestNoteName) && (
        <div className="flex items-baseline gap-3">
          {highestNoteName ? (
            <div className="flex items-baseline gap-1.5">
              <span className="text-xs text-zinc-500 dark:text-zinc-400">
                최고음
              </span>
              <span
                aria-label={`최고음 ${highestNoteName}`}
                className="text-base font-semibold text-zinc-900 dark:text-zinc-50"
              >
                {highestNoteName}
              </span>
            </div>
          ) : null}
          {lowestNoteName ? (
            <div className="flex items-baseline gap-1.5">
              <span className="text-xs text-zinc-500 dark:text-zinc-400">
                최저음
              </span>
              <span className="text-xs text-zinc-700 dark:text-zinc-300">
                {lowestNoteName}
              </span>
            </div>
          ) : null}
        </div>
      )}

      <div className="flex items-center justify-between gap-3">
        <div className="flex min-w-0 items-center gap-2">
          {song.genre ? (
            <span className="inline-flex items-center rounded-full bg-zinc-100 px-2.5 py-0.5 text-xs font-medium text-zinc-700 dark:bg-zinc-800 dark:text-zinc-300">
              {song.genre}
            </span>
          ) : null}
          {item ? (
            <span className="truncate text-xs text-zinc-500 dark:text-zinc-400">
              {item.matchReason}
            </span>
          ) : null}
        </div>
        {item ? (
          <span className="shrink-0 font-mono text-xs text-zinc-600 dark:text-zinc-400">
            score {item.score.toFixed(2)}
          </span>
        ) : null}
      </div>
    </>
  );

  // href가 있으면 카드 전체를 링크로 감싼다. <li>는 그대로 두고, 내부 <a>가
  // 클릭/포커스를 흡수한다. <a>는 카드 전체 영역을 차지하도록 grid 형태로 배치.
  if (href) {
    return (
      <li className="group rounded-2xl bg-white ring-1 ring-zinc-200 transition hover:ring-zinc-300 hover:shadow-md focus-within:ring-2 focus-within:ring-zinc-400 dark:bg-zinc-900 dark:ring-zinc-800 dark:hover:ring-zinc-600 dark:focus-within:ring-zinc-500">
        <Link
          href={href}
          aria-label={`${song.title} 상세 보기`}
          className="flex flex-col gap-3 rounded-2xl p-4 focus:outline-none focus-visible:ring-2 focus-visible:ring-zinc-500"
        >
          {body}
        </Link>
      </li>
    );
  }

  return (
    <li
      tabIndex={0}
      className="group flex flex-col gap-3 rounded-2xl bg-white p-4 ring-1 ring-zinc-200 transition hover:ring-zinc-300 hover:shadow-md focus-within:ring-2 focus-within:ring-zinc-400 focus:outline-none focus:ring-2 focus:ring-zinc-500 dark:bg-zinc-900 dark:ring-zinc-800 dark:hover:ring-zinc-600 dark:focus-within:ring-zinc-500"
    >
      {body}
    </li>
  );
}

type DifficultyBadgeProps = {
  difficulty: Difficulty;
};

function DifficultyBadge({ difficulty }: DifficultyBadgeProps) {
  const tone = difficultyTone(difficulty);
  return (
    <span
      aria-label={`가창 난이도 ${difficultyLabel(difficulty)}`}
      className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold ${tone}`}
    >
      {difficultyLabel(difficulty)}
    </span>
  );
}

function difficultyTone(difficulty: Difficulty): string {
  switch (difficulty) {
    case "EASY":
      return "bg-emerald-100 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300";
    case "NORMAL":
      return "bg-amber-100 text-amber-700 dark:bg-amber-950 dark:text-amber-300";
    case "HARD":
      return "bg-rose-100 text-rose-700 dark:bg-rose-950 dark:text-rose-300";
  }
}

/**
 * 응답에 `difficulty`가 있으면 그것을, 없고 `lowMidi`/`highMidi`가 있으면
 * client-side 계산값을, 둘 다 없으면 null을 돌려준다.
 */
function resolveDifficulty(
  song: RecommendedSongResponse["song"],
): Difficulty | null {
  if (song.difficulty) {
    return song.difficulty;
  }
  if (typeof song.lowMidi === "number" && typeof song.highMidi === "number") {
    return deriveDifficulty(song.lowMidi, song.highMidi);
  }
  return null;
}

function formatMusicalKey(key: string): string {
  if (key === "UNKNOWN") {
    return "Unknown";
  }
  // ex) C_SHARP_MAJOR → C# Major
  return key
    .replace(/_SHARP/g, "#")
    .replace(/_/g, " ")
    .replace(/\b(\w)(\w*)/g, (_, head: string, tail: string) => {
      return `${head}${tail.toLowerCase()}`;
    });
}

/**
 * Skeleton 카드. 추천 결과 로딩 중에 SongCard 자리에 표시한다.
 */
export function SongCardSkeleton() {
  return (
    <li className="flex flex-col gap-3 rounded-2xl bg-white p-4 ring-1 ring-zinc-200 dark:bg-zinc-900 dark:ring-zinc-800">
      <div className="flex items-start justify-between gap-4">
        <div className="flex min-w-0 flex-1 flex-col gap-2">
          <div className="h-3 w-8 animate-pulse rounded bg-zinc-200 dark:bg-zinc-800" />
          <div className="h-5 w-2/3 animate-pulse rounded bg-zinc-200 dark:bg-zinc-800" />
          <div className="h-4 w-1/3 animate-pulse rounded bg-zinc-200 dark:bg-zinc-800" />
        </div>
        <div className="flex flex-col items-end gap-2">
          <div className="h-5 w-14 animate-pulse rounded-full bg-zinc-200 dark:bg-zinc-800" />
          <div className="h-3 w-10 animate-pulse rounded bg-zinc-200 dark:bg-zinc-800" />
        </div>
      </div>
      <div className="flex items-baseline gap-3">
        <div className="h-4 w-20 animate-pulse rounded bg-zinc-200 dark:bg-zinc-800" />
        <div className="h-3 w-14 animate-pulse rounded bg-zinc-200 dark:bg-zinc-800" />
      </div>
      <div className="flex items-center justify-between">
        <div className="h-4 w-1/2 animate-pulse rounded bg-zinc-200 dark:bg-zinc-800" />
        <div className="h-3 w-12 animate-pulse rounded bg-zinc-200 dark:bg-zinc-800" />
      </div>
    </li>
  );
}
