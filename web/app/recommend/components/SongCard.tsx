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
import { useId, useState, type MouseEvent, type ReactNode } from "react";

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
import {
  buildScoreBreakdown,
  type RecommendationBreakdownItem,
  type UserVoiceRange,
} from "@/lib/scoreBreakdown";
import { useLikesStore } from "@/store/likes";

/**
 * Props 분기:
 *   - `item: RecommendedSongResponse` — 추천 결과 카드. rank/score/matchReason 노출.
 *   - `song: SongResponse` — 검색 결과 카드. 추천 컨텍스트 필드는 모두 숨김.
 *
 * 두 모드 모두 동일한 시각 표현(난이도/최고음/장르/키)을 공유하며, 옵셔널 `href`로
 * 카드 전체를 클릭 가능한 링크로 만들 수 있다.
 */
/**
 * `userVoiceRange`는 추천 컨텍스트(`item`)에서만 의미가 있다 — 카드 펼침 시
 * "음역 적합" 항목 계산에 사용된다. 검색 컨텍스트(`song`)나 히스토리에서 voiceRange를
 * 모르는 경우에는 옵셔널로 비워두면 해당 항목이 자동 생략된다.
 */
type SongCardProps =
  | {
      item: RecommendedSongResponse;
      song?: never;
      href?: string;
      userVoiceRange?: UserVoiceRange | null;
    }
  | {
      song: SongResponse;
      item?: never;
      href?: string;
      userVoiceRange?: never;
    };

export function SongCard(props: SongCardProps) {
  const song: SongResponse = "item" in props && props.item ? props.item.song : props.song!;
  const item: RecommendedSongResponse | null =
    "item" in props && props.item ? props.item : null;
  const href: string | undefined = props.href;
  const userVoiceRange: UserVoiceRange | null =
    "item" in props && props.userVoiceRange ? props.userVoiceRange : null;
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

  // 펼침 토글(closes #141)은 추천 컨텍스트(item)에서만 노출. matchReason을 더 깊게
  // 풀어 보여주는 "Why this song?" 패널이라 검색/스켈레톤에서는 의미가 없다.
  // 또한 href 모드에서도 <a> 내부에 button을 두는 것은 HTML 위반이므로 link 외부에
  // 별도 footer로 렌더한다.
  const breakdownPanel = item ? (
    <MatchReasonExpander item={item} userVoiceRange={userVoiceRange} />
  ) : null;

  // 좋아요 버튼(closes #176) — 추천/검색 두 컨텍스트 모두 노출. href 모드에서는
  // <a> 안에 button을 두면 클릭이 부모 링크로 새 나가므로 link 외부에 둔다.
  const likePanel = (
    <LikeButton songId={song.id} songTitle={song.title} />
  );

  // href가 있으면 본문(body)만 링크로 감싸고, footer(breakdown + like)는 링크 외부에 둔다.
  // 이렇게 하면 펼침/좋아요 버튼 클릭이 페이지 이동을 트리거하지 않으면서도 본문 클릭은
  // 그대로 곡 상세로 이동한다.
  if (href) {
    return (
      <li className="group flex flex-col rounded-2xl bg-white ring-1 ring-zinc-200 transition hover:ring-zinc-300 hover:shadow-md focus-within:ring-2 focus-within:ring-zinc-400 dark:bg-zinc-900 dark:ring-zinc-800 dark:hover:ring-zinc-600 dark:focus-within:ring-zinc-500">
        <Link
          href={href}
          aria-label={`${song.title} 상세 보기`}
          className="flex flex-col gap-3 rounded-2xl p-4 focus:outline-none focus-visible:ring-2 focus-visible:ring-zinc-500"
        >
          {body}
        </Link>
        <div className="flex flex-col gap-2 border-t border-zinc-100 px-4 py-3 dark:border-zinc-800">
          {breakdownPanel}
          {likePanel}
        </div>
      </li>
    );
  }

  return (
    <li
      tabIndex={0}
      className="group flex flex-col gap-3 rounded-2xl bg-white p-4 ring-1 ring-zinc-200 transition hover:ring-zinc-300 hover:shadow-md focus-within:ring-2 focus-within:ring-zinc-400 focus:outline-none focus:ring-2 focus:ring-zinc-500 dark:bg-zinc-900 dark:ring-zinc-800 dark:hover:ring-zinc-600 dark:focus-within:ring-zinc-500"
    >
      {body}
      {breakdownPanel}
      {likePanel}
    </li>
  );
}

/**
 * 좋아요 토글 버튼 (closes #176, spec PR D 일부).
 *
 * - aria-pressed로 토글 상태 노출 — 스크린 리더가 "눌림/안 눌림"으로 읽는다.
 * - 텍스트 라벨도 "좋아요" / "좋아요 취소"로 바뀌어 시각 사용자에게도 명확.
 * - 클릭 이벤트의 preventDefault/stopPropagation은 부모 링크가 없으므로 불필요하지만
 *   미래 변경에 대비해 명시적으로 가두어 둔다. (href 모드에서는 link 외부에 있어
 *   실제로는 새 나갈 일이 없다.)
 * - 백엔드 미구현 상태이므로 zustand persist store만 갱신 (낙관적 업데이트 아닌
 *   "유일한 source of truth"). 백엔드 PR B 머지 후에는 React Query mutation으로
 *   대체될 예정 — 본 store는 오프라인 fallback으로 격하된다.
 */
type LikeButtonProps = {
  songId: number;
  songTitle: string;
};

function LikeButton({ songId, songTitle }: LikeButtonProps) {
  const liked = useLikesStore((state) => state.likedSongIds.includes(songId));
  const toggleLike = useLikesStore((state) => state.toggleLike);

  function handleClick(event: MouseEvent<HTMLButtonElement>) {
    // href 모드에서 link 외부 footer에 있긴 하지만, 만약 미래에 위치가 바뀌어도
    // 안전하도록 명시적으로 부모 click 전파를 막는다.
    event.preventDefault();
    event.stopPropagation();
    toggleLike(songId);
  }

  return (
    <button
      type="button"
      onClick={handleClick}
      aria-pressed={liked}
      aria-label={liked ? `${songTitle} 좋아요 취소` : `${songTitle} 좋아요`}
      className={`inline-flex items-center gap-1.5 self-start rounded-full px-2.5 py-1 text-xs font-medium transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-zinc-500 ${
        liked
          ? "bg-rose-100 text-rose-700 hover:bg-rose-200 dark:bg-rose-950 dark:text-rose-300 dark:hover:bg-rose-900"
          : "text-zinc-600 hover:bg-zinc-100 dark:text-zinc-300 dark:hover:bg-zinc-800"
      }`}
    >
      <span aria-hidden="true">{liked ? "❤️" : "🤍"}</span>
      <span>{liked ? "좋아요 취소" : "좋아요"}</span>
    </button>
  );
}

/**
 * matchReason 펼침 토글 (closes #141, Spotify "Why this song?" 영감).
 *
 * 접힘 상태:
 *   - "자세히 보기" 버튼 + ChevronDown 아이콘. aria-expanded=false.
 * 펼침 상태:
 *   - 버튼 라벨 "접기" + Chevron 회전. aria-expanded=true.
 *   - 본문에 점수 분해 항목들을 dl/dt/dd 의미적 구조로 표시.
 *   - client-side 추정값인 경우 안내 footnote 노출.
 */
type MatchReasonExpanderProps = {
  item: RecommendedSongResponse;
  userVoiceRange: UserVoiceRange | null;
};

function MatchReasonExpander({
  item,
  userVoiceRange,
}: MatchReasonExpanderProps) {
  const [expanded, setExpanded] = useState(false);
  const panelId = useId();
  const breakdown = buildScoreBreakdown(item, userVoiceRange);
  const hasEstimated = breakdown.some((entry) => entry.estimated);

  return (
    <div className="flex flex-col gap-2">
      <button
        type="button"
        onClick={() => setExpanded((prev) => !prev)}
        aria-expanded={expanded}
        aria-controls={panelId}
        className="inline-flex items-center gap-1 self-start rounded-full px-2 py-1 text-xs font-medium text-zinc-700 transition-colors hover:bg-zinc-100 hover:text-zinc-900 focus:outline-none focus-visible:ring-2 focus-visible:ring-zinc-500 dark:text-zinc-300 dark:hover:bg-zinc-800 dark:hover:text-zinc-50"
      >
        <span>{expanded ? "접기" : "자세히 보기"}</span>
        <ChevronDownIcon expanded={expanded} />
      </button>
      {expanded ? (
        <div
          id={panelId}
          className="flex flex-col gap-2 rounded-xl bg-zinc-50 p-3 text-xs text-zinc-700 dark:bg-zinc-950 dark:text-zinc-300"
        >
          <dl className="flex flex-col gap-1.5">
            {breakdown.map((entry) => (
              <BreakdownRow key={entry.key} entry={entry} />
            ))}
          </dl>
          {hasEstimated ? (
            <p className="text-[11px] text-zinc-500 dark:text-zinc-500">
              ※ 점수 분해는 클라이언트 추정값입니다. 백엔드 산출값이 추가되면 자동으로 교체됩니다.
            </p>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}

type BreakdownRowProps = {
  entry: RecommendationBreakdownItem;
};

function BreakdownRow({ entry }: BreakdownRowProps) {
  const percent = Math.round(Math.max(0, Math.min(1, entry.score)) * 100);
  return (
    <div className="flex items-center justify-between gap-3">
      <dt className="flex min-w-0 flex-col">
        <span className="font-medium text-zinc-800 dark:text-zinc-200">
          {entry.label}
        </span>
        <span className="truncate text-[11px] text-zinc-500 dark:text-zinc-400">
          {entry.detail}
        </span>
      </dt>
      <dd
        aria-label={`${entry.label} 점수 ${percent}%`}
        className="shrink-0 font-mono text-xs tabular-nums text-zinc-700 dark:text-zinc-300"
      >
        {percent}%
      </dd>
    </div>
  );
}

type ChevronDownIconProps = {
  expanded: boolean;
};

function ChevronDownIcon({ expanded }: ChevronDownIconProps) {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 16 16"
      width="12"
      height="12"
      className={`transition-transform ${expanded ? "rotate-180" : ""}`}
    >
      <path
        d="M3 6l5 5 5-5"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.5"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
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
