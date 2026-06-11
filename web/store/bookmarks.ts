/**
 * 북마크 저장소 (closes #184, spec PR D).
 *
 * 역할:
 *   - source of truth는 BE (`GET /api/v1/sessions/{id}/bookmarks`).
 *   - 이 store는 낙관적 업데이트 캐시 + 오프라인 fallback.
 *   - SongCard의 북마크 토글 mutation이 onMutate 단계에서 즉시 갱신, onError에서 롤백.
 *
 * 정책/보안은 `likes.ts`와 동일 — songId만 보관, PII 없음, localStorage 한정.
 * 좋아요와 북마크는 서로 독립이라 별도 영속 key(`mobruji-bookmarks`)로 둔다.
 */

"use client";

import { create } from "zustand";
import { createJSONStorage, persist } from "zustand/middleware";

type BookmarksState = {
  /** 북마크한 곡 ID 목록. prepend 정책(최근이 앞). */
  bookmarkedSongIds: number[];
  /** 토글 — 없으면 prepend, 있으면 제거. 멱등. */
  toggleBookmark: (songId: number) => void;
  /** 단건 조회. */
  isBookmarked: (songId: number) => boolean;
  /** BE fetch 결과 hydration. */
  setBookmarkedSongIds: (ids: readonly number[]) => void;
  /** 전체 초기화. 테스트/디버그 전용. */
  clearBookmarks: () => void;
};

export const useBookmarksStore = create<BookmarksState>()(
  persist(
    (set, get) => ({
      bookmarkedSongIds: [],
      toggleBookmark: (songId) => {
        set((state) => {
          if (state.bookmarkedSongIds.includes(songId)) {
            return {
              bookmarkedSongIds: state.bookmarkedSongIds.filter(
                (id) => id !== songId,
              ),
            };
          }
          return {
            bookmarkedSongIds: [songId, ...state.bookmarkedSongIds],
          };
        });
      },
      isBookmarked: (songId) => get().bookmarkedSongIds.includes(songId),
      setBookmarkedSongIds: (ids) =>
        set({ bookmarkedSongIds: ids.slice() }),
      clearBookmarks: () => set({ bookmarkedSongIds: [] }),
    }),
    {
      name: "mobruji-bookmarks",
      storage: createJSONStorage(() => localStorage),
    },
  ),
);
