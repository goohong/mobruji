"use client";

/**
 * 북마크한 곡 페이지 (`/bookmarks`, closes #184, spec PR D).
 *
 * 구조는 `/likes` 와 거의 동일 — source of truth는 BE
 * (`GET /api/v1/sessions/{id}/bookmarks`). React Query → zustand 동기화 → useQueries
 * 로 곡 메타데이터 페치 → SongCard.
 *
 * 좋아요와 북마크를 별도 페이지로 두는 이유:
 *   - 좋아요는 "감정적 호불호", 북마크는 "다시 부를 곡 메모" 라는 의미 분리.
 *   - 한 화면에 섞으면 카테고리가 흐려진다. spec PR D 결정.
 */

import { useEffect, useState } from "react";
import Link from "next/link";
import { useQueries, useQuery } from "@tanstack/react-query";

import { SongCard } from "@/app/recommend/components/SongCard";
import { SongDetailModal } from "@/app/recommend/components/SongDetailModal";
import { SongDetailContent } from "@/app/recommend/components/SongDetailContent";
import { ApiError } from "@/lib/api/client";
import { readBookmarksBySessionId } from "@/lib/api/feedback";
import { readSongById, type SongResponse } from "@/lib/api/song";
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

  useEffect(() => {
    if (bookmarksQuery.data) {
      setBookmarkedSongIds(bookmarksQuery.data.map((entry) => entry.songId));
    }
  }, [bookmarksQuery.data, setBookmarkedSongIds]);

  if (bookmarksQuery.isPending && Boolean(sessionId)) {
    return <LoadingBookmarks />;
  }

  const bookmarkedSongIds = (bookmarksQuery.data ?? []).map(
    (entry) => entry.songId,
  );

  if (bookmarkedSongIds.length === 0) {
    return <EmptyBookmarks />;
  }

  return <BookmarksContent bookmarkedSongIds={bookmarkedSongIds} />;
}

type BookmarksContentProps = {
  bookmarkedSongIds: number[];
};

function BookmarksContent({ bookmarkedSongIds }: BookmarksContentProps) {
  const results = useQueries({
    queries: bookmarkedSongIds.map((songId) => ({
      queryKey: ["song", songId],
      queryFn: ({ signal }: { signal?: AbortSignal }) =>
        readSongById(songId, signal),
      staleTime: 60_000,
      retry: (failureCount: number, error: Error) => {
        if (error instanceof ApiError && error.status === 404) {
          return false;
        }
        return failureCount < 1;
      },
    })),
  });

  const songs: SongResponse[] = [];
  const missingSongIds: number[] = [];
  let pendingCount = 0;

  results.forEach((result, index) => {
    const songId = bookmarkedSongIds[index];
    if (result.isPending) {
      pendingCount += 1;
      return;
    }
    if (result.isError) {
      missingSongIds.push(songId);
      return;
    }
    if (result.data) {
      songs.push(result.data);
    }
  });

  return (
    <main className="flex flex-1 flex-col items-center bg-zinc-50 px-6 py-12 dark:bg-zinc-950">
      <div className="w-full max-w-2xl flex flex-col gap-6">
        <header className="space-y-2">
          <p className="text-xs font-medium uppercase tracking-widest text-zinc-500 dark:text-zinc-400">
            Bookmarks
          </p>
          <h1 className="text-2xl font-semibold text-zinc-900 dark:text-zinc-50">
            북마크한 곡
          </h1>
          {/*
            (closes #431) 카운트 영역을 스크린 리더 라이브 영역으로 마킹한다.
            BE 응답으로 bookmarkedSongIds 가 채워지거나 곡 메타데이터 N건 페치가 끝나며
            메시지가 바뀌므로 polite live 로 알린다 (/likes 와 동일 패턴, PR #428 참고).
          */}
          <p
            data-testid="bookmarks-count-live"
            role="status"
            aria-live="polite"
            aria-atomic="true"
            className="text-sm text-zinc-600 dark:text-zinc-400"
          >
            총 {bookmarkedSongIds.length}곡을 북마크했어요.
            {pendingCount > 0 ? ` (불러오는 중 ${pendingCount}곡)` : null}
          </p>
        </header>

        {songs.length > 0 ? (
          <BookmarkSongListWithModal songs={songs} />
        ) : pendingCount === 0 ? (
          <p
            role="status"
            className="rounded-2xl border border-dashed border-zinc-300 bg-white p-4 text-sm text-zinc-600 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-300"
          >
            북마크한 곡 정보를 불러올 수 없어요. 잠시 후 다시 시도해주세요.
          </p>
        ) : null}

        {missingSongIds.length > 0 ? (
          <p className="text-xs text-zinc-500 dark:text-zinc-400">
            ※ {missingSongIds.length}곡은 카탈로그에서 찾을 수 없어 표시하지 못했어요.
          </p>
        ) : null}
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
      <ul aria-label="북마크한 곡 목록" className="flex flex-col gap-2">
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
        titleLabel={selected ? selected.title : ""}
      >
        {selected ? <SongDetailContent song={selected} /> : null}
      </SongDetailModal>
    </>
  );
}

function LoadingBookmarks() {
  return (
    <main
      role="status"
      aria-label="북마크한 곡 불러오는 중"
      className="flex flex-1 flex-col items-center justify-center bg-zinc-50 px-6 py-12 dark:bg-zinc-950"
    >
      <p className="text-sm text-zinc-500 dark:text-zinc-400">
        북마크한 곡을 불러오는 중…
      </p>
    </main>
  );
}

function EmptyBookmarks() {
  return (
    <main className="flex flex-1 flex-col items-center justify-center bg-zinc-50 px-6 py-12 text-center dark:bg-zinc-950">
      <div className="w-full max-w-md flex flex-col items-center gap-4">
        <h1 className="text-xl font-semibold text-zinc-900 dark:text-zinc-50">
          아직 북마크한 곡이 없어요
        </h1>
        <p className="text-sm text-zinc-600 dark:text-zinc-400">
          추천 결과나 곡 검색에서 🔖 를 눌러 다시 부르고 싶은 곡을 모아보세요.
        </p>
        <div className="flex flex-col gap-2 w-full">
          <Link
            href="/recommend"
            className="inline-flex h-11 items-center justify-center rounded-full bg-zinc-900 px-5 text-sm font-medium text-white hover:bg-zinc-700 dark:bg-zinc-50 dark:text-zinc-900 dark:hover:bg-zinc-200"
          >
            추천 받으러 가기
          </Link>
          <Link
            href="/songs"
            className="inline-flex h-11 items-center justify-center rounded-full border border-zinc-300 px-5 text-sm font-medium text-zinc-700 hover:bg-zinc-100 dark:border-zinc-700 dark:text-zinc-200 dark:hover:bg-zinc-800"
          >
            곡 검색하기
          </Link>
        </div>
      </div>
    </main>
  );
}
