"use client";

/**
 * 북마크한 곡 페이지 (`/bookmarks`, closes #184, spec PR D, fix #845).
 *
 * 구조는 `/likes` 와 거의 동일 — source of truth는 BE
 * (`GET /api/v1/sessions/{id}/bookmarks`). React Query → zustand 동기화 → BE join
 * 응답의 `responses[].song` 을 SongCard 로 그대로 전달.
 *
 * closes #845 — 응답 wrapper `BookmarkListResponse` 채택 (곡 메타 join). 기존
 * `readSongById` N+1 fan-out 제거.
 *
 * 좋아요와 북마크를 별도 페이지로 두는 이유:
 *   - 좋아요는 "감정적 호불호", 북마크는 "다시 부를 곡 메모" 라는 의미 분리.
 *   - 한 화면에 섞으면 카테고리가 흐려진다. spec PR D 결정.
 */

import { useEffect, useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";

import { Skeleton } from "@/components/ui";
import { SongCard } from "@/app/recommend/components/SongCard";
import { SongDetailSheet } from "@/app/recommend/components/SongDetailSheet";
import { SongDetailContent } from "@/app/recommend/components/SongDetailContent";
import { readBookmarksBySessionId } from "@/lib/api/feedback";
import type { SongResponse } from "@/lib/api/song";
import { formatSongDisplayTitle } from "@/lib/songTitle";
import { useBookmarksStore } from "@/store/bookmarks";
import { useSessionStore } from "@/store/session";

export default function BookmarksPage() {
  const ensureSessionId = useSessionStore((state) => state.ensureSessionId);
  const setBookmarkedSongIds = useBookmarksStore(
    (state) => state.setBookmarkedSongIds,
  );

  const sessionId = useSessionStore((state) => state.sessionId);
  useEffect(() => {
    if (!sessionId) {
      ensureSessionId();
    }
  }, [sessionId, ensureSessionId]);

  const bookmarksQuery = useQuery({
    queryKey: ["bookmarks", sessionId],
    queryFn: ({ signal }) =>
      readBookmarksBySessionId(sessionId as string, signal),
    enabled: Boolean(sessionId),
    staleTime: 30_000,
  });

  // closes #845 — 응답 wrapper `responses[].song.id` 에서 songId 추출.
  useEffect(() => {
    if (bookmarksQuery.data) {
      setBookmarkedSongIds(
        bookmarksQuery.data.responses.map((entry) => entry.song.id),
      );
    }
  }, [bookmarksQuery.data, setBookmarkedSongIds]);

  if (bookmarksQuery.isPending && Boolean(sessionId)) {
    return <LoadingBookmarks />;
  }

  const songs: SongResponse[] = (bookmarksQuery.data?.responses ?? []).map(
    (entry) => entry.song,
  );

  if (songs.length === 0) {
    return <EmptyBookmarks />;
  }

  return <BookmarksContent songs={songs} />;
}

type BookmarksContentProps = {
  songs: SongResponse[];
};

function BookmarksContent({ songs }: BookmarksContentProps) {
  /*
   * ADR-0018 단계 4 PR 7 — /bookmarks 페이지 토큰 swap (#1163 /recommend 패턴 확장).
   *
   * swap 한 요소 (first-paint 핵심):
   *  1) <main> 배경 + padding : bg-zinc-50 dark:bg-zinc-950, px-6 py-12 → tokens
   *  2) h1 "북마크한 곡" : text-zinc-900 dark:text-zinc-50 → --text-primary
   *  3) 카운트 라이브 p : text-zinc-600 dark:text-zinc-400 → --text-secondary
   *
   * 미swap (후속 PR 양보 — #1163 정책 일치):
   *  - "Bookmarks" caption (text-zinc-500 dark:text-zinc-400) — caption 토큰 매핑 미정
   *  - SongCard / SongDetailModal — 별도 컴포넌트 (PR 6 #1160 머지 후 진행)
   *
   * 다크 모드: tokens.css `:where(html.dark)` selector 자동 swap. swap 한 element 에서
   * `dark:` prefix 제거.
   */
  return (
    <main className="flex flex-1 flex-col items-center bg-[var(--bg-subtle)] px-[var(--page-padding-x)] py-[var(--page-padding-y)]">
      <div className="w-full max-w-2xl lg:max-w-5xl flex flex-col gap-6">
        <header className="space-y-2">
          <p className="text-xs font-medium uppercase tracking-widest text-[var(--text-caption)]">
            Bookmarks
          </p>
          <h1 className="text-2xl font-semibold text-[var(--text-primary)]">
            북마크한 곡
          </h1>
          {/*
            (closes #431) 카운트 영역을 스크린 리더 라이브 영역으로 마킹한다.
            BE 응답으로 bookmarkedSongIds 가 채워지면 메시지가 바뀌므로 polite live 로
            알린다 (/likes 와 동일 패턴, PR #428 참고).
          */}
          <p
            data-testid="bookmarks-count-live"
            role="status"
            aria-live="polite"
            aria-atomic="true"
            className="text-sm text-[var(--text-secondary)]"
          >
            총 {songs.length}곡을 북마크했어요.
          </p>
        </header>

        <BookmarkSongListWithModal songs={songs} />
      </div>
    </main>
  );
}

/**
 * 북마크 목록의 카드 + 모달 묶음 (closes #323). likes 페이지의 SongListWithModal 과 동일한 패턴.
 */
type BookmarkSongListWithModalProps = {
  songs: SongResponse[];
};

function BookmarkSongListWithModal({ songs }: BookmarkSongListWithModalProps) {
  const [selected, setSelected] = useState<SongResponse | null>(null);
  return (
    <>
      {/*
       * 와이드 뷰포트 2컬럼 그리드 (closes #1717). items-start 로 카드가 행 높이에 맞춰
       * 늘어나지 않게 해 본문↔footer void 를 제거한다. 모바일은 단일 컬럼.
       */}
      <ul
        aria-label="북마크한 곡 목록"
        className="grid grid-cols-1 items-start gap-3 lg:grid-cols-2"
      >
        {songs.map((song, index) => (
          <SongCard
            key={song.id}
            song={song}
            index={index}
            onShowDetail={() => setSelected(song)}
          />
        ))}
      </ul>
      <SongDetailSheet
        open={selected !== null}
        onClose={() => setSelected(null)}
        titleLabel={selected ? formatSongDisplayTitle(selected) : ""}
      >
        {selected ? <SongDetailContent song={selected} /> : null}
      </SongDetailSheet>
    </>
  );
}

function LoadingBookmarks() {
  // 실제 BookmarksContent(헤더 + 곡 리스트) 윤곽을 shimmer 스켈레톤으로 미리 그린다 (#1493).
  return (
    <main
      role="status"
      aria-label="북마크한 곡 불러오는 중"
      className="flex flex-1 flex-col items-center bg-[var(--bg-subtle)] px-[var(--page-padding-x)] py-[var(--page-padding-y)]"
    >
      <div className="w-full max-w-2xl lg:max-w-5xl flex flex-col gap-6">
        <header className="space-y-2">
          <Skeleton className="h-3 w-20" />
          <Skeleton className="h-8 w-40" />
        </header>
        {/* 실제 결과 그리드(lg:grid-cols-2)와 동일 배치로 로딩 → 결과 레이아웃 점프 제거. */}
        <ul className="grid grid-cols-1 items-start gap-3 lg:grid-cols-2">
          {Array.from({ length: 4 }).map((_, rowIndex) => (
            <li key={rowIndex}>
              <Skeleton className="h-20 w-full rounded-[var(--radius-lg)]" />
            </li>
          ))}
        </ul>
      </div>
    </main>
  );
}

function EmptyBookmarks() {
  return (
    <main className="flex flex-1 flex-col items-center justify-center bg-[var(--bg-subtle)] px-[var(--page-padding-x)] py-[var(--page-padding-y)] text-center">
      <div className="w-full max-w-md flex flex-col items-center gap-4">
        <h1 className="text-xl font-semibold text-[var(--text-primary)]">
          아직 북마크한 곡이 없어요
        </h1>
        <p className="text-sm text-[var(--text-secondary)]">
          추천 결과나 곡 검색에서 🔖 를 눌러 다시 부르고 싶은 곡을 모아보세요.
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
