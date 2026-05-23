/**
 * 좋아요/북마크 토글 mutation 훅 (closes #846).
 *
 * AS-IS:
 *   `SongCard.tsx` 의 LikeButton/BookmarkButton + `SongDetailContent.tsx` 의
 *   DetailLikeButton/DetailBookmarkButton 4 블록에 거의 동일한 `useMutation` +
 *   `onMutate (낙관 토글)` + `onSuccess (BE 응답 보정 + invalidateQueries)` +
 *   `onError (롤백 + safeLog + 인라인 에러)` 복붙이 있었음. SongDetailContent 측은
 *   `useAutoDismissMessage` 까지 인라인 `useState + useEffect + setTimeout` 으로 다시
 *   적었음.
 *
 * TO-BE:
 *   `useLikeToggleMutation` / `useBookmarkToggleMutation` 두 훅이
 *   - 낙관 토글 → BE 호출 → 응답 보정 → 쿼리 invalidation
 *   - 실패 시 store 롤백 + safeLog + 자동 dismiss 에러 메시지
 *   전체 라이프사이클을 캡슐화한다. 4 컴포넌트는 className/size 만 분기.
 *
 * 두 훅이 거의 동일하지만 store/엔드포인트가 달라 굳이 generic 한 추상화는 두지 않는다
 * (가독성·디버깅 비용 우선).
 *
 * 사용 예:
 *   const {
 *     liked,
 *     toggle,
 *     isPending,
 *     errorMessage,
 *     clearError,
 *   } = useLikeToggleMutation(songId);
 *
 * 보안:
 *   sessionId 는 PII (logging.ts §SENSITIVE_KEYS). safeLog.error 가 자동 마스킹.
 */

"use client";

import { useCallback } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";

import {
  toggleBookmark as toggleBookmarkApi,
  toggleLike as toggleLikeApi,
} from "@/lib/api/feedback";
import { safeLog } from "@/lib/logging";
import { useAutoDismissMessage } from "@/lib/useAutoDismissMessage";
import { useBookmarksStore } from "@/store/bookmarks";
import { useLikesStore } from "@/store/likes";
import { useSessionStore } from "@/store/session";

/**
 * 인터랙션 실패(좋아요/북마크) 인라인 안내 자동 dismiss 지속 시간.
 *
 * SongCard / SongDetailContent 양쪽에서 의도적으로 같은 값을 썼음. 한곳에 모아
 * 두 환경에서 자연스럽게 통일된다.
 */
export const INTERACTION_FEEDBACK_DURATION_MS = 3000;

export type UseLikeToggleMutationResult = {
  /** 현재 좋아요 상태 (zustand selector). */
  liked: boolean;
  /** 클릭 핸들러에서 호출 — sessionId 확보 + 낙관 토글 + BE 호출. */
  toggle: () => void;
  /** mutation 진행 중 (중복 클릭 방지용 disabled/aria-busy 에 사용). */
  isPending: boolean;
  /** 실패 시 표시할 인라인 메시지 (null 이면 alert 노출 안 함). */
  errorMessage: string | null;
  /** 새 시도 직전 에러 메시지를 즉시 제거. */
  clearError: () => void;
};

/**
 * 좋아요 토글 mutation 훅 (closes #846).
 *
 * 흐름:
 *   1. `toggle()` → useSessionStore.ensureSessionId() 로 sessionId 확보.
 *   2. mutation.mutate 호출 — onMutate 에서 zustand store 를 즉시 토글(낙관).
 *   3. BE 응답 도착 onSuccess: 응답 `liked` 값으로 store 보정 + ['likes', sessionId]
 *      쿼리 invalidate (`/likes` 페이지 자동 재페치).
 *   4. onError: 낙관 변경 롤백 + safeLog (PII 마스킹) + 인라인 에러 메시지.
 */
export function useLikeToggleMutation(
  songId: number,
): UseLikeToggleMutationResult {
  const liked = useLikesStore((state) => state.likedSongIds.includes(songId));
  const toggleLikeInStore = useLikesStore((state) => state.toggleLike);
  const ensureSessionId = useSessionStore((state) => state.ensureSessionId);
  const queryClient = useQueryClient();
  const {
    message: errorMessage,
    setMessage: setErrorMessage,
    clear: clearError,
  } = useAutoDismissMessage(INTERACTION_FEEDBACK_DURATION_MS);

  const mutation = useMutation({
    mutationFn: ({ sessionId }: { sessionId: string }) =>
      toggleLikeApi({ sessionId, songId }),
    onMutate: () => {
      // 낙관 토글 — UI 응답성 우선. 롤백 시 다시 토글하면 원상복귀돼서
      // 별도 snapshot 불필요.
      toggleLikeInStore(songId);
    },
    onSuccess: async (response, { sessionId }) => {
      // BE 응답 `liked` 가 낙관값과 다르면 보정 (race 상황 안전망).
      const currentlyLiked = useLikesStore
        .getState()
        .likedSongIds.includes(songId);
      if (currentlyLiked !== response.liked) {
        toggleLikeInStore(songId);
      }
      await queryClient.invalidateQueries({
        queryKey: ["likes", sessionId],
      });
    },
    onError: (error) => {
      toggleLikeInStore(songId);
      safeLog.error("[useLikeToggleMutation] 좋아요 토글 실패", error);
      setErrorMessage("좋아요 처리에 실패했어요. 다시 시도해 주세요.");
    },
  });

  const toggle = useCallback(() => {
    if (mutation.isPending) {
      return;
    }
    // 새 시도 시작 시 이전 에러 안내 즉시 제거 — alert 잔존 혼란 방지.
    clearError();
    const sessionId = ensureSessionId();
    mutation.mutate({ sessionId });
  }, [mutation, ensureSessionId, clearError]);

  return {
    liked,
    toggle,
    isPending: mutation.isPending,
    errorMessage,
    clearError,
  };
}

export type UseBookmarkToggleMutationResult = {
  bookmarked: boolean;
  toggle: () => void;
  isPending: boolean;
  errorMessage: string | null;
  clearError: () => void;
};

/**
 * 북마크 토글 mutation 훅 (closes #846).
 *
 * {@link useLikeToggleMutation} 와 동일 패턴 — store/엔드포인트/queryKey 만 다르다.
 */
export function useBookmarkToggleMutation(
  songId: number,
): UseBookmarkToggleMutationResult {
  const bookmarked = useBookmarksStore((state) =>
    state.bookmarkedSongIds.includes(songId),
  );
  const toggleBookmarkInStore = useBookmarksStore(
    (state) => state.toggleBookmark,
  );
  const ensureSessionId = useSessionStore((state) => state.ensureSessionId);
  const queryClient = useQueryClient();
  const {
    message: errorMessage,
    setMessage: setErrorMessage,
    clear: clearError,
  } = useAutoDismissMessage(INTERACTION_FEEDBACK_DURATION_MS);

  const mutation = useMutation({
    mutationFn: ({ sessionId }: { sessionId: string }) =>
      toggleBookmarkApi({ sessionId, songId }),
    onMutate: () => {
      toggleBookmarkInStore(songId);
    },
    onSuccess: async (response, { sessionId }) => {
      const currentlyBookmarked = useBookmarksStore
        .getState()
        .bookmarkedSongIds.includes(songId);
      if (currentlyBookmarked !== response.bookmarked) {
        toggleBookmarkInStore(songId);
      }
      await queryClient.invalidateQueries({
        queryKey: ["bookmarks", sessionId],
      });
    },
    onError: (error) => {
      toggleBookmarkInStore(songId);
      safeLog.error("[useBookmarkToggleMutation] 북마크 토글 실패", error);
      setErrorMessage("북마크 처리에 실패했어요. 다시 시도해 주세요.");
    },
  });

  const toggle = useCallback(() => {
    if (mutation.isPending) {
      return;
    }
    clearError();
    const sessionId = ensureSessionId();
    mutation.mutate({ sessionId });
  }, [mutation, ensureSessionId, clearError]);

  return {
    bookmarked,
    toggle,
    isPending: mutation.isPending,
    errorMessage,
    clearError,
  };
}
