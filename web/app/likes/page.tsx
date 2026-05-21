"use client";

/**
 * 좋아요한 곡 페이지 (`/likes`, closes #176, spec PR D 일부).
 *
 * - zustand persist(`useLikesStore`)에 누적된 songId 들을 메타데이터와 함께 카드 리스트로 노출.
 * - 각 카드: `SongCard` 의 검색 컨텍스트(`song` prop) 재사용 — rank/score/matchReason 없음.
 *   카드 자체에 좋아요 토글(❤️) 버튼이 이미 들어있어 이 페이지에서도 바로 취소 가능.
 * - 빈 상태: 추천/검색 CTA + 안내 문구.
 *
 * 결정 배경:
 *  - spec §5-6 은 v0.2 에서 `/likes` 전용 페이지를 만들지 않고 `/history` 탭으로만 두기로
 *    제안했지만, 백엔드가 없는 상태에서 client-side stub 가치 검증을 빠르게 하려면
 *    독립 라우트가 진입 동선이 명확하다. 백엔드 PR B 머지 후 spec Q2 결론에 따라
 *    `/history` 탭으로 흡수 또는 유지 결정.
 *  - 곡 메타데이터는 좋아요 store에 보관하지 않는다 (PII 분리/store 최소화).
 *    `readSongById` 를 useQueries 로 N건 일괄 페치 — staleTime 60초.
 */

import Link from "next/link";
import { useQueries } from "@tanstack/react-query";

import { SongCard } from "@/app/recommend/components/SongCard";
import { ApiError } from "@/lib/api/client";
import { readSongById, type SongResponse } from "@/lib/api/song";
import { useLikesStore } from "@/store/likes";

export default function LikesPage() {
  const likedSongIds = useLikesStore((state) => state.likedSongIds);

  if (likedSongIds.length === 0) {
    return <EmptyLikes />;
  }

  return <LikesContent likedSongIds={likedSongIds} />;
}

type LikesContentProps = {
  likedSongIds: number[];
};

function LikesContent({ likedSongIds }: LikesContentProps) {
  const results = useQueries({
    queries: likedSongIds.map((songId) => ({
      queryKey: ["song", songId],
      queryFn: ({ signal }: { signal?: AbortSignal }) =>
        readSongById(songId, signal),
      // 메타데이터는 자주 바뀌지 않음 — 좋아요 페이지 재진입 시 캐시 우선.
      staleTime: 60_000,
      retry: (failureCount: number, error: Error) => {
        // 404는 곡이 삭제됐을 가능성 — 재시도 무의미.
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
    const songId = likedSongIds[index];
    if (result.isPending) {
      pendingCount += 1;
      return;
    }
    if (result.isError) {
      // 404 ↔ 그 외 오류 분리: 404 는 "삭제된 곡" 안내, 그 외는 단순 누락 처리.
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
            Likes
          </p>
          <h1 className="text-2xl font-semibold text-zinc-900 dark:text-zinc-50">
            좋아한 곡
          </h1>
          <p className="text-sm text-zinc-600 dark:text-zinc-400">
            총 {likedSongIds.length}곡을 좋아했어요.
            {pendingCount > 0 ? ` (불러오는 중 ${pendingCount}곡)` : null}
          </p>
        </header>

        {songs.length > 0 ? (
          <ul aria-label="좋아한 곡 목록" className="flex flex-col gap-2">
            {songs.map((song) => (
              <SongCard
                key={song.id}
                song={song}
                href={`/songs/${song.id}`}
              />
            ))}
          </ul>
        ) : pendingCount === 0 ? (
          <p
            role="status"
            className="rounded-2xl border border-dashed border-zinc-300 bg-white p-4 text-sm text-zinc-600 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-300"
          >
            좋아한 곡 정보를 불러올 수 없어요. 잠시 후 다시 시도해주세요.
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

function EmptyLikes() {
  return (
    <main className="flex flex-1 flex-col items-center justify-center bg-zinc-50 px-6 py-12 text-center dark:bg-zinc-950">
      <div className="w-full max-w-md flex flex-col items-center gap-4">
        <h1 className="text-xl font-semibold text-zinc-900 dark:text-zinc-50">
          아직 좋아한 곡이 없어요
        </h1>
        <p className="text-sm text-zinc-600 dark:text-zinc-400">
          추천 결과나 곡 검색에서 ❤️ 를 눌러 좋아한 곡을 모아보세요.
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
