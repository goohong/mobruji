/**
 * 곡 상세 모달 본문 컨텐츠 (closes #323).
 *
 * SongDetailModal 의 children 으로 들어가 카드 클릭 시 펼쳐지는 풀 상세를 그린다.
 * 책임:
 *   - 앨범 커버 이미지 자리(또는 placeholder — closes #322 PR 2 에서 src 채움)
 *   - 곡 메타(아티스트/난이도/최고음·최저음/키/장르/mood/BPM/발매년)
 *   - 추천 컨텍스트일 때만: matchReason 전문 + 점수 breakdown(클라이언트 추정)
 *   - YouTube 검색 링크 (새 탭)
 *   - 좋아요/북마크 큰 액션 버튼
 *
 * 카드 표면이 "한눈에 보이는 정보" 만 남도록 만든 정책(검수 피드백)의 짝.
 * 추천/검색 두 컨텍스트 모두 지원하기 위해 props 분기는 SongCard 와 동일한 형태.
 *
 * ## 좋아요/북마크 버튼 — 모달 전용 큰 사이즈
 * SongCard 안 LikeButton/BookmarkButton 은 carded 카드 footer 톤에 맞춘 컴팩트 사이즈다.
 * 모달은 사용자가 결정을 내리는 화면이라 hit target 을 더 크게(min-h-12) 두고,
 * 같은 mutation 흐름(낙관 토글 + queryClient invalidate)을 재사용한다. 별도 컴포넌트로
 * 분리하지 않고 SongCard 의 LikeButton/BookmarkButton 을 그대로 사용 — 시각 차이는
 * Tailwind className 만으로 충분히 처리 가능하므로 중복 구현을 피한다.
 */

"use client";

import { type MouseEvent, useState } from "react";

import type {
  RecommendedSongResponse,
  SongResponse,
} from "@/lib/api/recommendation";
import {
  deriveDifficulty,
  difficultyLabel,
  type Difficulty,
} from "@/lib/difficulty";
import {
  useBookmarkToggleMutation,
  useLikeToggleMutation,
} from "@/lib/hooks/useFeedbackToggleMutation";
import { midiToNoteName } from "@/lib/notes";
import {
  buildScoreBreakdown,
  type RecommendationBreakdownItem,
  type UserVoiceRange,
} from "@/lib/scoreBreakdown";
import { Chip } from "@/components/ui";

type SongDetailContentProps =
  | {
      item: RecommendedSongResponse;
      song?: never;
      userVoiceRange?: UserVoiceRange | null;
    }
  | {
      song: SongResponse;
      item?: never;
      userVoiceRange?: never;
    };

export function SongDetailContent(props: SongDetailContentProps) {
  const song: SongResponse =
    "item" in props && props.item ? props.item.song : props.song!;
  const item: RecommendedSongResponse | null =
    "item" in props && props.item ? props.item : null;
  const userVoiceRange: UserVoiceRange | null =
    "item" in props && props.userVoiceRange ? props.userVoiceRange : null;

  const difficulty = resolveDifficulty(song);
  const highestNoteName =
    typeof song.highMidi === "number" ? midiToNoteName(song.highMidi) : null;
  const lowestNoteName =
    typeof song.lowMidi === "number" ? midiToNoteName(song.lowMidi) : null;
  const keyLabel = formatMusicalKey(song.keyOriginal);

  return (
    <div className="flex flex-col gap-5">
      <header className="flex flex-col gap-3">
        <AlbumCover song={song} />
        <div className="flex flex-col gap-1">
          {item ? (
            <p className="text-xs font-medium text-zinc-500 dark:text-zinc-400">
              #{item.rankPosition}
            </p>
          ) : null}
          <p className="text-base text-zinc-700 dark:text-zinc-300">
            {song.artist}
          </p>
          <div className="flex flex-wrap items-center gap-2 pt-1">
            {difficulty ? <DifficultyBadge difficulty={difficulty} /> : null}
            <span className="inline-flex items-center rounded-full bg-zinc-100 px-2.5 py-0.5 text-xs font-medium text-zinc-700 dark:bg-zinc-800 dark:text-zinc-300">
              키 {keyLabel}
            </span>
            {song.genre ? <Chip tone="neutral">{song.genre}</Chip> : null}
            {song.mood ? (
              <span className="inline-flex items-center rounded-full bg-zinc-100 px-2.5 py-0.5 text-xs font-medium text-zinc-700 dark:bg-zinc-800 dark:text-zinc-300">
                {song.mood}
              </span>
            ) : null}
          </div>
        </div>
      </header>

      {highestNoteName || lowestNoteName ? (
        <section
          aria-label="음역"
          className="flex items-baseline gap-6 rounded-xl bg-zinc-50 p-4 dark:bg-zinc-950"
        >
          {highestNoteName ? (
            <NoteCell label="최고음" value={highestNoteName} />
          ) : null}
          {lowestNoteName ? (
            <NoteCell label="최저음" value={lowestNoteName} />
          ) : null}
        </section>
      ) : null}

      <section
        aria-label="메타 정보"
        className="grid grid-cols-2 gap-x-4 gap-y-3 text-sm sm:grid-cols-3"
      >
        <MetaCell label="발매 연도" value={song.releaseYear ?? null} />
        <MetaCell label="BPM" value={song.bpm ?? null} />
        <MetaCell label="언어" value={song.language ?? null} />
        <MetaCell label="TJ 번호" value={song.tjNumber ?? null} />
        <MetaCell label="KY 번호" value={song.kyNumber ?? null} />
        <MetaCell label="출처" value={song.metadataSource} />
      </section>

      {item ? (
        <MatchReasonSection item={item} userVoiceRange={userVoiceRange} />
      ) : null}

      <section
        aria-label="액션"
        className="flex flex-col gap-3 border-t border-zinc-200 pt-4 dark:border-zinc-800"
      >
        <div className="flex flex-wrap gap-2">
          <DetailLikeButton songId={song.id} songTitle={song.title} />
          <DetailBookmarkButton songId={song.id} songTitle={song.title} />
        </div>
        <YouTubeSearchLink songTitle={song.title} songArtist={song.artist} />
      </section>
    </div>
  );
}

/**
 * 앨범 커버 placeholder (closes #322).
 *
 * BE Song 엔티티에 `albumCoverUrl` 컬럼이 추가되면(be 32 PR) 자동으로 채워진다.
 * 그 전까지는 null/loading-fail 시 음표 SVG placeholder 를 그린다.
 *
 * - `<img loading="lazy">` 로 lazy load — 모달이 닫혀 있는 동안 네트워크 비용 0.
 * - alt 텍스트는 "<곡 제목> 앨범 커버" — 스크린 리더 친화.
 * - onError 시 placeholder 로 fallback (BE 응답 URL 이 404/CORS 등으로 실패한 경우).
 */
type AlbumCoverProps = {
  song: SongResponse;
};

function AlbumCover({ song }: AlbumCoverProps) {
  // closes #322 — BE PR #337 에서 Song.albumCoverUrl 컬럼 + iTunes Search backfill 도입.
  // backfill 미적용/fuzzy match 실패 곡은 null → placeholder. img 로딩 실패(404/CORS)
  // 시에도 onError 로 placeholder 로 fallback. eager 로드는 모달이 열린 직후만
  // 발생하므로 lazy 가 아닌 default load 가 자연스럽다 (lazy 는 thumbnail 에서).
  const url = song.albumCoverUrl ?? null;
  const [failed, setFailed] = useState(false);

  if (!url || failed) {
    return <AlbumCoverPlaceholder size="large" songTitle={song.title} />;
  }

  return (
    <div className="flex justify-center">
      {/*
       * next/image 미사용 의도(closes #322): iTunes CDN 도메인을 next.config.images.domains
       * 화이트리스트에 추가해야 하는데 next.config.* 가 보호 영역이라 본 PR 범위에서는
       * <img> 로 유지. 후속 PR 에서 도메인 등록 + next/image 마이그레이션을 별도로 분리.
       */}
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        src={url}
        alt={`${song.title} 앨범 커버`}
        onError={() => setFailed(true)}
        className="h-48 w-48 rounded-2xl object-cover ring-1 ring-zinc-200 dark:ring-zinc-800"
      />
    </div>
  );
}

type AlbumCoverPlaceholderProps = {
  size: "large" | "thumbnail";
  songTitle: string;
};

/**
 * 다크 모드 호환 그라데이션 + 음표 SVG. 앨범 커버 부재 시 일관된 비주얼.
 * thumbnail 사이즈는 SongCard 요약에서, large 사이즈는 모달에서 사용한다.
 */
export function AlbumCoverPlaceholder({
  size,
  songTitle,
}: AlbumCoverPlaceholderProps) {
  const sizeClass =
    size === "large"
      ? "h-48 w-48 rounded-2xl"
      : "h-14 w-14 rounded-xl";
  return (
    <div className={size === "large" ? "flex justify-center" : ""}>
      <div
        role="img"
        aria-label={`${songTitle} 앨범 커버 (이미지 없음)`}
        className={`flex items-center justify-center bg-gradient-to-br from-zinc-200 to-zinc-300 ring-1 ring-zinc-200 dark:from-zinc-800 dark:to-zinc-700 dark:ring-zinc-800 ${sizeClass}`}
      >
        <MusicNoteIcon size={size === "large" ? 56 : 24} />
      </div>
    </div>
  );
}

type MusicNoteIconProps = {
  size: number;
};

function MusicNoteIcon({ size }: MusicNoteIconProps) {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 24 24"
      width={size}
      height={size}
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
      className="text-zinc-500 dark:text-zinc-400"
    >
      <path d="M9 18V6l10-2v12" />
      <circle cx="6" cy="18" r="3" />
      <circle cx="16" cy="16" r="3" />
    </svg>
  );
}

/**
 * 추천 컨텍스트 전용 — matchReason 한 줄 + 점수 breakdown 표.
 * 모달은 펼침/접힘 토글이 필요 없으므로 항상 펼친 상태로 노출한다.
 */
type MatchReasonSectionProps = {
  item: RecommendedSongResponse;
  userVoiceRange: UserVoiceRange | null;
};

function MatchReasonSection({ item, userVoiceRange }: MatchReasonSectionProps) {
  const breakdown = buildScoreBreakdown(item, userVoiceRange);
  const hasEstimated = breakdown.some((entry) => entry.estimated);
  return (
    <section
      aria-label="추천 사유"
      className="flex flex-col gap-3 rounded-xl bg-zinc-50 p-4 dark:bg-zinc-950"
    >
      <div className="flex items-baseline justify-between gap-3">
        <h3 className="text-sm font-semibold text-zinc-900 dark:text-zinc-50">
          추천 사유
        </h3>
        <span className="font-mono text-xs text-zinc-600 dark:text-zinc-400">
          score {item.score.toFixed(2)}
        </span>
      </div>
      <p className="text-sm text-zinc-700 dark:text-zinc-300">
        {item.matchReason}
      </p>
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
    </section>
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
        <span className="text-sm font-medium text-zinc-800 dark:text-zinc-200">
          {entry.label}
        </span>
        <span className="truncate text-xs text-zinc-500 dark:text-zinc-400">
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

type NoteCellProps = {
  label: string;
  value: string;
};

function NoteCell({ label, value }: NoteCellProps) {
  return (
    <div className="flex flex-col gap-1">
      <span className="text-xs text-zinc-500 dark:text-zinc-400">{label}</span>
      <span
        aria-label={`${label} ${value}`}
        className="text-2xl font-semibold text-zinc-900 dark:text-zinc-50"
      >
        {value}
      </span>
    </div>
  );
}

type MetaCellProps = {
  label: string;
  value: string | number | null;
};

function MetaCell({ label, value }: MetaCellProps) {
  const display: string =
    value === null || value === undefined || value === "" ? "-" : String(value);
  return (
    <div className="flex flex-col gap-0.5">
      <span className="text-xs text-zinc-500 dark:text-zinc-400">{label}</span>
      <span className="text-sm font-medium text-zinc-800 dark:text-zinc-200">
        {display}
      </span>
    </div>
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

function resolveDifficulty(song: SongResponse): Difficulty | null {
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
  return key
    .replace(/_SHARP/g, "#")
    .replace(/_/g, " ")
    .replace(/\b(\w)(\w*)/g, (_, head: string, tail: string) => {
      return `${head}${tail.toLowerCase()}`;
    });
}

/**
 * 모달 전용 — SongCard 의 LikeButton 과 동일 흐름이지만 hit target 이 더 크다.
 * 별도 컴포넌트로 분리해 className 만 다르게 둔다. mutation 로직은 카피지만 두 환경의
 * 시각 차이가 명확해서 prop 추상화보다 분리가 가독성에 좋다.
 */
type DetailFeedbackButtonProps = {
  songId: number;
  songTitle: string;
};

function DetailLikeButton({ songId, songTitle }: DetailFeedbackButtonProps) {
  // closes #846 — 모달 전용 큰 사이즈(min-h-12). mutation 흐름은 카드와 100% 공유,
  // 표면 className 만 다르다. 인라인 setTimeout 도 useAutoDismissMessage 로 통일됨.
  const { liked, toggle, isPending, errorMessage } =
    useLikeToggleMutation(songId);

  function handleClick(event: MouseEvent<HTMLButtonElement>) {
    event.preventDefault();
    event.stopPropagation();
    toggle();
  }

  return (
    <div className="flex flex-col gap-1">
      <button
        type="button"
        onClick={handleClick}
        disabled={isPending}
        aria-pressed={liked}
        aria-busy={isPending}
        aria-label={liked ? `${songTitle} 좋아요 취소` : `${songTitle} 좋아요`}
        className={`inline-flex min-h-12 items-center gap-2 self-start rounded-full px-5 py-2.5 text-sm font-semibold transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-zinc-500 disabled:cursor-progress disabled:opacity-60 ${
          liked
            ? "bg-rose-100 text-rose-700 hover:bg-rose-200 dark:bg-rose-950 dark:text-rose-300 dark:hover:bg-rose-900"
            : "border border-zinc-300 text-zinc-700 hover:bg-zinc-100 dark:border-zinc-700 dark:text-zinc-200 dark:hover:bg-zinc-800"
        }`}
      >
        <span aria-hidden="true">{liked ? "❤️" : "🤍"}</span>
        <span>{liked ? "좋아요 취소" : "좋아요"}</span>
      </button>
      {errorMessage ? (
        <p
          role="alert"
          aria-live="assertive"
          className="text-xs text-rose-700 dark:text-rose-300"
        >
          {errorMessage}
        </p>
      ) : null}
    </div>
  );
}

function DetailBookmarkButton({ songId, songTitle }: DetailFeedbackButtonProps) {
  // closes #846 — DetailLikeButton 과 대칭. mutation 흐름 공유, 표면 className 만 분기.
  const { bookmarked, toggle, isPending, errorMessage } =
    useBookmarkToggleMutation(songId);

  function handleClick(event: MouseEvent<HTMLButtonElement>) {
    event.preventDefault();
    event.stopPropagation();
    toggle();
  }

  return (
    <div className="flex flex-col gap-1">
      <button
        type="button"
        onClick={handleClick}
        disabled={isPending}
        aria-pressed={bookmarked}
        aria-busy={isPending}
        aria-label={
          bookmarked ? `${songTitle} 북마크 해제` : `${songTitle} 북마크`
        }
        className={`inline-flex min-h-12 items-center gap-2 self-start rounded-full px-5 py-2.5 text-sm font-semibold transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-zinc-500 disabled:cursor-progress disabled:opacity-60 ${
          bookmarked
            ? "bg-amber-100 text-amber-800 hover:bg-amber-200 dark:bg-amber-950 dark:text-amber-300 dark:hover:bg-amber-900"
            : "border border-zinc-300 text-zinc-700 hover:bg-zinc-100 dark:border-zinc-700 dark:text-zinc-200 dark:hover:bg-zinc-800"
        }`}
      >
        <span aria-hidden="true">🔖</span>
        <span>{bookmarked ? "북마크 해제" : "북마크"}</span>
      </button>
      {errorMessage ? (
        <p
          role="alert"
          aria-live="assertive"
          className="text-xs text-amber-700 dark:text-amber-300"
        >
          {errorMessage}
        </p>
      ) : null}
    </div>
  );
}

/**
 * YouTube 검색 링크 — 모달 전용 큰 사이즈. SongCard 의 동일 컴포넌트와 동작/접근성 동일.
 */
type YouTubeSearchLinkProps = {
  songTitle: string;
  songArtist: string;
};

function YouTubeSearchLink({ songTitle, songArtist }: YouTubeSearchLinkProps) {
  const query = `${songTitle} ${songArtist}`.trim();
  const params = new URLSearchParams({ search_query: query });
  const href = `https://www.youtube.com/results?${params.toString()}`;
  return (
    <a
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      aria-label={`${songTitle} YouTube에서 듣기 (새 탭)`}
      className="inline-flex min-h-12 w-fit items-center gap-2 rounded-full bg-zinc-900 px-5 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-zinc-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-zinc-500 dark:bg-zinc-50 dark:text-zinc-900 dark:hover:bg-zinc-200"
    >
      <span aria-hidden="true">▶</span>
      <span>YouTube에서 듣기</span>
    </a>
  );
}

/**
 * 외부에서 placeholder 만 단독으로 쓰고 싶을 때 (예: 카드 thumbnail) export.
 * AlbumCover 컴포넌트는 모달 전용이라 export 하지 않고 placeholder 만 노출한다.
 */
export type { AlbumCoverPlaceholderProps };

type AlbumCoverThumbnailProps = {
  song: SongResponse;
};

/**
 * 카드 요약 thumbnail (closes #322) — SongCard 좌측 sm 영역에 사용.
 * url 이 있으면 lazy load, 실패/없으면 placeholder.
 */
export function AlbumCoverThumbnail({ song }: AlbumCoverThumbnailProps) {
  // closes #322 — SongCard 좌측 small (48~64px) thumbnail. loading="lazy" 로
  // 뷰포트 진입 시점에 페치 — 긴 리스트(추천 무한 스크롤, 검색 결과)에서 초기
  // 네트워크 비용 최소화. onError 시 placeholder 로 fallback.
  const url = song.albumCoverUrl ?? null;
  const [failed, setFailed] = useState(false);

  if (!url || failed) {
    return <AlbumCoverPlaceholder size="thumbnail" songTitle={song.title} />;
  }

  return (
    // next/image 미사용 의도: AlbumCover 와 동일 — 후속 PR 에서 도메인 등록 + 마이그레이션.
    // eslint-disable-next-line @next/next/no-img-element
    <img
      src={url}
      loading="lazy"
      alt={`${song.title} 앨범 커버`}
      onError={() => setFailed(true)}
      className="h-14 w-14 rounded-xl object-cover ring-1 ring-zinc-200 dark:ring-zinc-800"
    />
  );
}
