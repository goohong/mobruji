"use client";

/**
 * 좋아한 곡 페이지 (`/likes`, closes #176 + BE 연동 #184, fix #845).
 *
 * 데이터 흐름:
 *   1. `useSessionStore.ensureSessionId()` 로 sessionId 확보.
 *   2. React Query `['likes', sessionId]` 로 `readLikesBySessionId` 호출 — source of truth.
 *      응답은 `LikeListResponse` wrapper (closes #845) — `responses` 각 항목이
 *      `LikeWithSongResponse { id, song, likedAt }` 로 곡 메타까지 join 되어 있다.
 *   3. 응답 수신 시 `useLikesStore.setLikedSongIds` 로 zustand 캐시 동기화
 *      (SongCard의 낙관적 업데이트 기준이 BE와 일치하도록).
 *   4. `responses[].song` 을 그대로 SongCard 로 전달 — `readSongById` N+1 fan-out 제거
 *      (BE 가 join 응답을 내려주므로).
 *
 * 결정 배경:
 *  - spec §5-6 은 v0.2 에서 `/likes` 전용 페이지를 만들지 않고 `/history` 탭으로만 두기로
 *    제안했지만, 백엔드 연동 검증을 빠르게 하려면 독립 라우트가 진입 동선이 명확하다.
 *    spec Q2 결론에 따라 `/history` 탭으로 흡수 또는 유지 결정 (별도 PR).
 *  - 곡 메타데이터는 좋아요 store에 보관하지 않는다 (PII 분리/store 최소화).
 *
 * 보안: sessionId는 PII이므로 화면에 노출 금지, 로그는 `safeLog` 사용.
 */

import { useEffect, useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";

import { SongCard } from "@/app/recommend/components/SongCard";
import { SongDetailModal } from "@/app/recommend/components/SongDetailModal";
import { SongDetailContent } from "@/app/recommend/components/SongDetailContent";
import { readLikesBySessionId } from "@/lib/api/feedback";
import type { SongResponse } from "@/lib/api/song";
import { formatSongDisplayTitle } from "@/lib/songTitle";
import { useLikesStore } from "@/store/likes";
import { useSessionStore } from "@/store/session";

export default function LikesPage() {
  const ensureSessionId = useSessionStore((state) => state.ensureSessionId);
  const setLikedSongIds = useLikesStore((state) => state.setLikedSongIds);

  // 페이지 진입 시 sessionId 확보 — 미생성이면 즉시 생성.
  const sessionId = useSessionStore((state) => state.sessionId);
  useEffect(() => {
    if (!sessionId) {
      ensureSessionId();
    }
  }, [sessionId, ensureSessionId]);

  const likesQuery = useQuery({
    queryKey: ["likes", sessionId],
    queryFn: ({ signal }) =>
      readLikesBySessionId(sessionId as string, signal),
    enabled: Boolean(sessionId),
    staleTime: 30_000,
  });

  // BE 응답 → zustand store 동기화. SongCard의 낙관적 업데이트가 BE 기준에서 출발하도록.
  // closes #845 — 응답 wrapper `responses[].song.id` 에서 songId 추출.
  useEffect(() => {
    if (likesQuery.data) {
      setLikedSongIds(
        likesQuery.data.responses.map((entry) => entry.song.id),
      );
    }
  }, [likesQuery.data, setLikedSongIds]);

  if (likesQuery.isPending && Boolean(sessionId)) {
    return <LoadingLikes />;
  }

  // closes #845 — BE join 응답으로 곡 메타까지 한 번에 받는다 (N+1 제거).
  const songs: SongResponse[] = (likesQuery.data?.responses ?? []).map(
    (entry) => entry.song,
  );

  if (songs.length === 0) {
    return <EmptyLikes />;
  }

  return <LikesContent songs={songs} />;
}

type LikesContentProps = {
  songs: SongResponse[];
};

function LikesContent({ songs }: LikesContentProps) {
  /*
   * ADR-0018 단계 4 PR 7 — /likes 페이지 토큰 swap (#1163 /recommend 패턴 확장).
   *
   * swap 한 요소 (first-paint 핵심):
   *  1) <main> 배경 + padding : bg-zinc-50 dark:bg-zinc-950, px-6 py-12 → tokens
   *  2) h1 "좋아한 곡" : text-zinc-900 dark:text-zinc-50 → --text-primary
   *  3) 카운트 라이브 p : text-zinc-600 dark:text-zinc-400 → --text-secondary
   *
   * 미swap (후속 PR 양보 — #1163 정책 일치):
   *  - "Likes" caption (text-zinc-500 dark:text-zinc-400) — caption 토큰 매핑 미정
   *  - SongCard / SongDetailModal — 별도 컴포넌트 (PR 6 #1160 머지 후 진행)
   *
   * 다크 모드: tokens.css `:where(html.dark)` selector 자동 swap. swap 한 element 에서
   * `dark:` prefix 제거.
   */
  return (
    <main className="flex flex-1 flex-col items-center bg-[var(--bg-subtle)] px-[var(--page-padding-x)] py-[var(--page-padding-y)]">
      <div className="w-full max-w-2xl flex flex-col gap-6">
        <header className="space-y-2">
          <p className="text-xs font-medium uppercase tracking-widest text-[var(--text-caption)]">
            Likes
          </p>
          <h1 className="text-2xl font-semibold text-[var(--text-primary)]">
            좋아한 곡
          </h1>
          {/*
            (closes #431) 카운트 영역을 스크린 리더 라이브 영역으로 마킹한다.
            BE 응답으로 likedSongIds 가 채워지면 메시지가 바뀌므로 polite live 로 알린다
            (PR #428 /recommend 와 동일 패턴). 시각 표시는 그대로 유지하고 `aria-live` 만
            부여 — 별도 sr-only 영역을 중복으로 두면 시각/SR 텍스트가 어긋날 위험이 있어
            헤더 카피에 직접 부여.
          */}
          <p
            data-testid="likes-count-live"
            role="status"
            aria-live="polite"
            aria-atomic="true"
            className="text-sm text-[var(--text-secondary)]"
          >
            총 {songs.length}곡을 좋아했어요.
          </p>
        </header>

        <SongListWithModal songs={songs} />
      </div>
    </main>
  );
}

/**
 * 좋아요 목록의 카드 + 모달 묶음 (closes #323).
 * 카드 클릭 시 페이지 이동 대신 상세 모달이 열린다. 좋아한 곡은 추천 컨텍스트가 아니라
 * matchReason/score 가 없으므로 SongDetailContent 도 song prop 으로 받는다.
 */
type SongListWithModalProps = {
  songs: SongResponse[];
};

function SongListWithModal({ songs }: SongListWithModalProps) {
  const [selected, setSelected] = useState<SongResponse | null>(null);
  return (
    <>
      <ul aria-label="좋아한 곡 목록" className="flex flex-col gap-2">
        {songs.map((song) => (
          <SongCard
            key={song.id}
            song={song}
            onShowDetail={() => setSelected(song)}
          />
        ))}
      </ul>
      <SongDetailModal
        open={selected !== null}
        onClose={() => setSelected(null)}
        titleLabel={selected ? formatSongDisplayTitle(selected) : ""}
      >
        {selected ? <SongDetailContent song={selected} /> : null}
      </SongDetailModal>
    </>
  );
}

function LoadingLikes() {
  return (
    <main
      role="status"
      aria-label="좋아한 곡 불러오는 중"
      className="flex flex-1 flex-col items-center justify-center bg-[var(--bg-subtle)] px-[var(--page-padding-x)] py-[var(--page-padding-y)]"
    >
      <p className="text-sm text-[var(--text-caption)]">
        좋아한 곡을 불러오는 중…
      </p>
    </main>
  );
}

function EmptyLikes() {
  return (
    <main className="flex flex-1 flex-col items-center justify-center bg-[var(--bg-subtle)] px-[var(--page-padding-x)] py-[var(--page-padding-y)] text-center">
      <div className="w-full max-w-md flex flex-col items-center gap-4">
        <h1 className="text-xl font-semibold text-[var(--text-primary)]">
          아직 좋아한 곡이 없어요
        </h1>
        <p className="text-sm text-[var(--text-secondary)]">
          추천 결과나 곡 검색에서 ❤️ 를 눌러 좋아한 곡을 모아보세요.
        </p>
        <div className="flex flex-col gap-2 w-full">
          <Link
            href="/recommend"
            className="inline-flex h-11 items-center justify-center rounded-full bg-[var(--brand-500)] px-5 text-sm font-medium text-white transition-colors duration-[var(--duration-base)] hover:bg-[var(--brand-600)] hover:shadow-[var(--shadow-brand)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--brand-500)] focus-visible:ring-offset-2"
          >
            추천 받으러 가기
          </Link>
          <Link
            href="/songs"
            className="inline-flex h-11 items-center justify-center rounded-full border border-[var(--border)] px-5 text-sm font-medium text-[var(--text-secondary)] hover:bg-[var(--bg-muted)]"
          >
            곡 검색하기
          </Link>
        </div>
      </div>
    </main>
  );
}
