"use client";

/**
 * 곡 상세 페이지 (`/songs/[id]`).
 *
 * 이슈 #100 / PR #101:
 *   - 검색(`/songs`) 또는 추천(`/recommend`)에서 곡 카드 클릭 시 진입한다.
 *   - 곡 1개에 대해 카드보다 자세한 정보를 한 화면에 보여준다.
 *
 * 노출 정보:
 *   - 제목(크게) / 아티스트
 *   - 가창 난이도 라벨 (`difficulty.ts` 재사용, BE 필드 우선 → 없으면 lowMidi/highMidi 기반)
 *   - 최고음/최저음 음표명 (notes.ts MIDI → 음표 변환)
 *   - 키, 장르 칩, mood
 *   - 발매 연도, 언어(한국어 라벨), BPM, TJ/KY 번호 (내부 출처 코드는 노이즈라 비노출, #1715)
 *
 * 데이터 로딩:
 *   - `readSongById(id)` (GET /api/v1/songs/{id})를 React Query로 호출.
 *   - 로딩 중: skeleton 표시.
 *   - 404 (`ApiError.status === 404`): "곡을 찾을 수 없습니다" + 검색 페이지 CTA.
 *   - 그 외 오류: 에러 메시지 + "검색으로 돌아가기" CTA.
 *
 * 라우팅:
 *   - 동적 라우트(App Router `[id]`). `generateStaticParams`는 사용하지 않는다 — 곡 카탈로그가
 *     서버에서 변할 수 있으므로 항상 dynamic으로 처리한다.
 *   - 잘못된 id(숫자가 아닌 값)는 BE에 그대로 넘기지 않고 클라이언트에서 404 분기로 처리한다.
 *
 * CTA:
 *   - "추천 받기": 추천 페이지로 이동(`/recommend`). 음역대 미입력이면 추천 페이지 내부
 *     fallback이 음역대 입력 화면으로 안내한다.
 */

import { useParams } from "next/navigation";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";

import { ApiError } from "@/lib/api/client";
import { readSongById, type SongResponse } from "@/lib/api/song";
import {
  deriveDifficulty,
  difficultyLabel,
  type Difficulty,
} from "@/lib/difficulty";
import { midiToKoreanNoteName } from "@/lib/notes";
import { formatSongDisplayTitle } from "@/lib/songTitle";
import { formatLanguageLabel } from "@/lib/songMeta";
import { useLikesStore } from "@/store/likes";
import { AlbumCover } from "@/app/recommend/components/SongDetailContent";

export default function SongDetailPage() {
  const params = useParams<{ id: string }>();
  const rawId = params?.id;
  const songId = parseSongId(rawId);

  if (songId === null) {
    return <NotFoundView />;
  }

  return <SongDetailContent songId={songId} />;
}

type SongDetailContentProps = {
  songId: number;
};

function SongDetailContent({ songId }: SongDetailContentProps) {
  const query = useQuery<SongResponse, Error>({
    queryKey: ["song", songId],
    queryFn: ({ signal }) => readSongById(songId, signal),
    retry: (failureCount, error) => {
      // 404는 재시도 의미 없음. 그 외 일시 오류는 1회만 재시도.
      if (error instanceof ApiError && error.status === 404) {
        return false;
      }
      return failureCount < 1;
    },
  });

  if (query.isPending) {
    return <SongDetailSkeleton />;
  }

  if (query.isError) {
    if (query.error instanceof ApiError && query.error.status === 404) {
      return <NotFoundView />;
    }
    return (
      <Shell>
        <div
          role="alert"
          aria-live="assertive"
          className="flex flex-col gap-3 rounded-2xl border border-[var(--danger-border)] bg-[var(--danger-bg)] p-4"
        >
          <p className="text-sm text-[var(--danger-fg-strong)]">
            곡 정보를 불러오지 못했습니다.{" "}
            {query.error instanceof ApiError
              ? `${query.error.status}: ${query.error.message}`
              : query.error.message}
          </p>
          <Link
            href="/songs"
            className="self-start rounded-full bg-[var(--danger-cta-bg)] px-4 py-2 text-sm font-medium text-white hover:bg-[var(--danger-cta-bg-hover)]"
          >
            검색으로 돌아가기
          </Link>
        </div>
      </Shell>
    );
  }

  return <SongDetailView song={query.data} />;
}

type SongDetailViewProps = {
  song: SongResponse;
};

function SongDetailView({ song }: SongDetailViewProps) {
  const difficulty = resolveDifficulty(song);
  const highestNoteName =
    typeof song.highMidi === "number"
      ? midiToKoreanNoteName(song.highMidi)
      : null;
  const lowestNoteName =
    typeof song.lowMidi === "number"
      ? midiToKoreanNoteName(song.lowMidi)
      : null;
  const keyLabel = formatMusicalKey(song.keyOriginal);
  // closes #1715 — 내부 언어 코드("ko")를 한국어 라벨로. 매핑 불가 시 null → 셀 생략.
  const languageLabel = formatLanguageLabel(song.language);
  // closes #1284 — 한국 곡 한국어 표시 우선 (heading + 좋아요 aria-label 동일 표시).
  const displayTitle = formatSongDisplayTitle(song);

  return (
    <Shell>
      <nav aria-label="이전" className="mb-2">
        <Link
          href="/songs"
          className="text-sm text-[var(--text-caption)] underline-offset-4 hover:underline"
        >
          ← 검색으로 돌아가기
        </Link>
      </nav>

      <header className="flex flex-col gap-2">
        {/* closes #1666 — 단건 상세 페이지에도 앨범 커버 노출. 모달과 동일한 large 커버 +
            placeholder/onError fallback 을 SongDetailContent 에서 재사용한다.
            closes #1687 (PR7) — 카드 thumbnail 과 같은 view-transition-name 을 줘서
            /history → /songs/[id] 라우트 전환 시 hero morph 한다. */}
        <AlbumCover song={song} viewTransitionName={`album-${song.id}`} />
        <p className="text-xs font-medium uppercase tracking-widest text-[var(--text-caption)]">
          Song detail
        </p>
        <h1 className="text-3xl font-semibold text-[var(--text-primary)]">
          {displayTitle}
        </h1>
        <p className="text-base text-[var(--text-secondary)]">
          {song.artist}
        </p>
        <div className="pt-1">
          <DetailLikeButton songId={song.id} songTitle={displayTitle} />
        </div>
      </header>

      <section
        aria-label="가창 정보"
        className="flex flex-col gap-4 rounded-2xl bg-[var(--cta-secondary-bg)] p-5 ring-1 ring-[var(--ring-soft-detail)]"
      >
        <div className="flex flex-wrap items-center gap-3">
          {difficulty ? (
            <DifficultyBadge difficulty={difficulty} />
          ) : (
            <span className="text-xs text-[var(--text-caption)]">
              가창 난이도 정보가 아직 없어요
            </span>
          )}
          <span className="inline-flex items-center rounded-full bg-[var(--badge-neutral-bg)] px-2.5 py-0.5 text-xs font-medium text-[var(--badge-neutral-fg)]">
            키 {keyLabel}
          </span>
          {song.genre ? (
            <span className="inline-flex items-center rounded-full bg-[var(--badge-neutral-bg)] px-2.5 py-0.5 text-xs font-medium text-[var(--badge-neutral-fg)]">
              {song.genre}
            </span>
          ) : null}
          {song.mood ? (
            <span className="inline-flex items-center rounded-full bg-[var(--badge-neutral-bg)] px-2.5 py-0.5 text-xs font-medium text-[var(--badge-neutral-fg)]">
              {song.mood}
            </span>
          ) : null}
        </div>

        {highestNoteName || lowestNoteName ? (
          <dl className="grid grid-cols-2 gap-4 sm:grid-cols-3">
            {highestNoteName ? (
              <NoteCell label="최고음" value={highestNoteName} />
            ) : null}
            {lowestNoteName ? (
              <NoteCell label="최저음" value={lowestNoteName} />
            ) : null}
          </dl>
        ) : (
          <p className="text-xs text-[var(--text-caption)]">
            이 곡의 음역 정보(최고음/최저음)는 아직 등록되지 않았어요.
          </p>
        )}
      </section>

      <section
        aria-label="메타 정보"
        className="flex flex-col gap-3 rounded-2xl bg-[var(--cta-secondary-bg)] p-5 ring-1 ring-[var(--ring-soft-detail)]"
      >
        <h2 className="text-sm font-semibold text-[var(--text-primary)]">
          메타 정보
        </h2>
        <dl className="grid grid-cols-2 gap-x-6 gap-y-3 text-sm sm:grid-cols-3">
          {/* closes #1715 — 내부 출처(metadataSource) 노이즈 제거 + 언어 한국어 라벨(매핑 불가 시 셀 생략). */}
          <MetaCell label="발매 연도" value={song.releaseYear ?? null} />
          <MetaCell label="BPM" value={song.bpm ?? null} />
          {languageLabel ? (
            <MetaCell label="언어" value={languageLabel} />
          ) : null}
          <MetaCell label="TJ 번호" value={song.tjNumber ?? null} />
          <MetaCell label="KY 번호" value={song.kyNumber ?? null} />
        </dl>
      </section>

      <div className="flex flex-wrap gap-3">
        <Link
          href="/recommend"
          className="inline-flex h-11 items-center justify-center rounded-full bg-[var(--cta-neutral-bg)] px-5 text-sm font-medium text-[var(--cta-neutral-fg)] hover:bg-[var(--cta-neutral-bg-hover)]"
        >
          비슷한 곡 추천 받기
        </Link>
        <Link
          href="/voice-range"
          className="inline-flex h-11 items-center justify-center rounded-full border border-[var(--cta-secondary-border)] px-5 text-sm font-medium text-[var(--cta-secondary-fg)] hover:bg-[var(--cta-secondary-bg-hover)]"
        >
          음역대 입력하기
        </Link>
      </div>
    </Shell>
  );
}

/**
 * 곡 상세 페이지의 좋아요 버튼 (closes #176, spec PR D 일부).
 *
 * SongCard 내부 LikeButton과 동일한 시그널(aria-pressed + 좋아요 토글)이지만,
 * 상세 페이지에서는 더 큰 hit target/시인성을 위해 별도 스타일을 둔다.
 * 백엔드 PR B 머지 후에는 React Query mutation으로 교체 예정.
 */
type DetailLikeButtonProps = {
  songId: number;
  songTitle: string;
};

function DetailLikeButton({ songId, songTitle }: DetailLikeButtonProps) {
  const liked = useLikesStore((state) => state.likedSongIds.includes(songId));
  const toggleLike = useLikesStore((state) => state.toggleLike);

  return (
    <button
      type="button"
      onClick={() => toggleLike(songId)}
      aria-pressed={liked}
      aria-label={liked ? `${songTitle} 좋아요 취소` : `${songTitle} 좋아요`}
      className={`inline-flex h-9 items-center gap-1.5 rounded-full px-3 text-sm font-medium transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--cta-secondary-ring)] ${
        liked
          ? "bg-rose-100 text-rose-700 hover:bg-rose-200 dark:bg-rose-950 dark:text-rose-300 dark:hover:bg-rose-900"
          : "border border-[var(--cta-secondary-border)] text-[var(--cta-secondary-fg)] hover:bg-[var(--cta-secondary-bg-hover)]"
      }`}
    >
      <span aria-hidden="true">{liked ? "❤️" : "🤍"}</span>
      <span>{liked ? "좋아요 취소" : "좋아요"}</span>
    </button>
  );
}

type NoteCellProps = {
  label: string;
  value: string;
};

function NoteCell({ label, value }: NoteCellProps) {
  return (
    <div className="flex flex-col gap-1">
      <dt className="text-xs text-[var(--text-caption)]">{label}</dt>
      <dd
        aria-label={`${label} ${value}`}
        className="text-2xl font-semibold text-[var(--text-primary)]"
      >
        {value}
      </dd>
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
    <div className="flex flex-col gap-1">
      <dt className="text-xs text-[var(--text-caption)]">{label}</dt>
      <dd className="text-sm font-medium text-[var(--text-body-emphasis)]">
        {display}
      </dd>
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
      className={`inline-flex items-center rounded-full px-2.5 py-1 text-xs font-semibold ${tone}`}
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

type ShellProps = {
  children: React.ReactNode;
};

function Shell({ children }: ShellProps) {
  return (
    <main className="flex flex-1 flex-col items-center bg-[var(--bg-subtle)] px-6 py-12">
      <div className="flex w-full max-w-2xl flex-col gap-6">{children}</div>
    </main>
  );
}

function SongDetailSkeleton() {
  return (
    <Shell>
      <div
        role="status"
        aria-busy="true"
        aria-label="곡 정보를 불러오는 중"
        className="flex flex-col gap-4"
      >
        <div className="h-3 w-16 animate-pulse rounded bg-[var(--meter-track-bg)]" />
        <div className="h-8 w-2/3 animate-pulse rounded bg-[var(--meter-track-bg)]" />
        <div className="h-4 w-1/3 animate-pulse rounded bg-[var(--meter-track-bg)]" />
        <div className="mt-2 h-32 w-full animate-pulse rounded-2xl bg-[var(--meter-track-bg)]" />
        <div className="h-32 w-full animate-pulse rounded-2xl bg-[var(--meter-track-bg)]" />
      </div>
    </Shell>
  );
}

function NotFoundView() {
  return (
    <Shell>
      <div className="flex flex-col items-start gap-4 rounded-2xl border border-dashed border-[var(--cta-secondary-border)] bg-[var(--cta-secondary-bg)] p-6">
        <h1 className="text-xl font-semibold text-[var(--text-primary)]">
          곡을 찾을 수 없습니다
        </h1>
        <p className="text-sm text-[var(--text-secondary)]">
          요청하신 곡이 카탈로그에 없어요. 검색에서 다시 찾아보세요.
        </p>
        <Link
          href="/songs"
          className="inline-flex h-11 items-center justify-center rounded-full bg-[var(--cta-neutral-bg)] px-5 text-sm font-medium text-[var(--cta-neutral-fg)] hover:bg-[var(--cta-neutral-bg-hover)]"
        >
          검색으로 가기
        </Link>
      </div>
    </Shell>
  );
}

/**
 * URL param의 id를 양수 정수로 파싱. 비숫자/0 이하/NaN은 null로 떨어뜨려 NotFoundView로 보낸다.
 * (BE는 음수/0에 대해 404를 던지지만 네트워크 호출 없이 즉시 NotFound로 보내는 편이 UX상 빠르다.)
 */
function parseSongId(raw: string | string[] | undefined): number | null {
  if (raw === undefined) {
    return null;
  }
  const value = Array.isArray(raw) ? raw[0] : raw;
  if (!/^\d+$/.test(value)) {
    return null;
  }
  const parsed = Number.parseInt(value, 10);
  if (!Number.isFinite(parsed) || parsed <= 0) {
    return null;
  }
  return parsed;
}

/**
 * SongCard와 동일한 난이도 우선순위: BE 응답 difficulty → client-side derive → null.
 */
function resolveDifficulty(song: SongResponse): Difficulty | null {
  if (song.difficulty) {
    return song.difficulty;
  }
  if (typeof song.lowMidi === "number" && typeof song.highMidi === "number") {
    return deriveDifficulty(song.lowMidi, song.highMidi);
  }
  return null;
}

/**
 * SongCard와 동일한 키 표기: `C_SHARP_MAJOR` → `C# Major`, `UNKNOWN` → `Unknown`.
 */
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
