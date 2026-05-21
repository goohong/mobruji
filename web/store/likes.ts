/**
 * 좋아요 저장소 (클라이언트 전용, closes #176, spec PR D 일부).
 *
 * 배경:
 *   - Feature Spec `recommendation-history-and-feedback.md` PR D는 SongCard에
 *     좋아요/북마크 버튼을 추가한다. 그러나 백엔드 PR B(Like 엔티티 + API)는
 *     아직 머지되지 않아 BE 의존 없이 우선 client-side stub으로 가치 검증을
 *     선행한다. 백엔드가 추가되면 React Query mutation으로 교체하면서 이
 *     store는 오프라인 fallback / 낙관적 업데이트 캐시로 강등될 예정.
 *
 * 정책:
 *   - `Set<songId>` 의미적 의도이지만 zustand persist는 Set을 직렬화하지 못해
 *     내부적으로 `number[]`로 보관한다. 외부에는 `isLiked`/`toggleLike`만 노출.
 *   - 토글 멱등 — 같은 songId 두 번 호출 시 원상복귀.
 *   - 상한 없음. v0.2 PoC 기준 한 사용자가 누를 수 있는 좋아요 개수가
 *     localStorage(약 5MB) 한도를 위협할 수준이 아니다.
 *
 * 보안 (rev 사이클 9 / PR #129 정책 준수):
 *   - localStorage에만 저장 — 사용자 본인 브라우저에 한정.
 *   - songId만 보관, PII 없음.
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
      clearLikes: () => set({ likedSongIds: [] }),
    }),
    {
      name: "mobruji-likes",
      storage: createJSONStorage(() => localStorage),
    },
  ),
);
