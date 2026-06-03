/**
 * 좋아요 저장소 (closes #176, backend 동기화 #184, spec PR D).
 *
 * 역할 (be 14 / PR #179 머지 후):
 *   - **낙관적 업데이트 캐시 + 오프라인 fallback**으로 격하.
 *   - source of truth는 BE (`GET /api/v1/sessions/{id}/likes`)이며, 페이지 로드
 *     시 React Query가 fetch 결과로 `setLikedSongIds`를 호출해 store를 동기화한다.
 *   - 토글은 SongCard에서 React Query mutation으로 BE 호출 + onMutate 단계에서
 *     이 store를 즉시 갱신(낙관적). onError에서 롤백.
 *
 * 정책:
 *   - `Set<songId>` 의미적 의도이지만 zustand persist는 Set을 직렬화하지 못해
 *     내부적으로 `number[]`로 보관. 외부에는 `isLiked`/`toggleLike` 등만 노출.
 *   - 토글 멱등 — 같은 songId 두 번 호출 시 원상복귀 (BE도 동일 의미).
 *   - 상한 없음. v0.2 PoC 기준 localStorage 한도를 위협할 수준 아님.
 *
 * 보안 (rev 사이클 9 / PR #129 정책):
 *   - localStorage에만 저장 — 사용자 본인 브라우저에 한정.
 *   - songId만 보관, PII 없음. sessionId는 별도 `useSessionStore`에 있음.
 */

"use client";

import { create } from "zustand";
import { persist, createJSONStorage } from "zustand/middleware";

type LikesState = {
  /**
   * 좋아요한 곡 ID 목록. 의미상 Set이지만 직렬화 호환을 위해 배열.
   * 순서는 prepend 정책(최근에 좋아요한 것이 앞).
   */
  likedSongIds: number[];
  /** 토글 — 없으면 추가(prepend), 있으면 제거. */
  toggleLike: (songId: number) => void;
  /** 조회 — Set 조회를 의도. 호출 측에서 selector로 쓰지 말 것(매번 새 array iterate). */
  isLiked: (songId: number) => boolean;
  /**
   * BE 응답을 그대로 반영. React Query `readLikesBySessionId` 결과 hydration에 사용.
   * 입력 배열은 그대로 보존되며 중복 제거는 호출 측 책임(BE는 unique 보장).
   */
  setLikedSongIds: (ids: readonly number[]) => void;
  /** 전체 초기화. 테스트/디버그 전용. */
  clearLikes: () => void;
};

export const useLikesStore = create<LikesState>()(
  persist(
    (set, get) => ({
      likedSongIds: [],
      toggleLike: (songId) => {
        set((state) => {
          if (state.likedSongIds.includes(songId)) {
            return {
              likedSongIds: state.likedSongIds.filter((id) => id !== songId),
            };
          }
          // 새 좋아요는 prepend — `/likes` 페이지에서 "최근에 좋아요한 곡" 순으로 보이게.
          return { likedSongIds: [songId, ...state.likedSongIds] };
        });
      },
      isLiked: (songId) => get().likedSongIds.includes(songId),
      setLikedSongIds: (ids) => set({ likedSongIds: ids.slice() }),
      clearLikes: () => set({ likedSongIds: [] }),
    }),
    {
      name: "mobruji-likes",
      storage: createJSONStorage(() => localStorage),
      // #1105 회귀 가드: shape 변경 시 옛 localStorage 를 그대로 hydrate 하지 않도록
      // version 을 명시한다. migrate 는 v1 baseline passthrough — version bump 시 변환 hook.
      version: 1,
      migrate: (persisted) => persisted as LikesState,
    },
  ),
);
