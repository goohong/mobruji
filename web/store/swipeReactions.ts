/**
 * 스와이프 선곡 반응 저장소 (#1489 — 쇼츠식 스와이프 선곡 UX).
 *
 * 역할:
 *   - 스와이프 덱에서 사용자가 한 곡씩 넘긴 반응(`like` / `pass`)을 곡 ID 기준으로 기록한다.
 *   - 이 로그는 "다음 추천 신호" 의 토대이자, 향후 **부른곡 기반 모드** 의 입력 신호다.
 *     좋아요는 선호 신호, 패스는 회피 신호로 후속 추천 알고리즘이 활용할 수 있다.
 *
 * 정책 (session.ts `excludedSongIds` 패턴 미러):
 *   - 같은 곡을 다시 스와이프하면 최신 반응으로 덮어쓴다 (곡당 1개 반응만 유지).
 *   - 삽입 순서를 유지하되 상한(`MAX_SWIPE_REACTIONS`) 초과 시 가장 오래된 것부터 만료.
 *   - `localStorage` 영속 — songId/반응만 보관, PII 없음 (sessionId 는 별도 store).
 */

"use client";

import { create } from "zustand";
import { persist, createJSONStorage } from "zustand/middleware";

export type SwipeReaction = "like" | "pass";

export type SwipeReactionEntry = {
  songId: number;
  reaction: SwipeReaction;
};

/**
 * 누적 반응 상한. session.ts `MAX_EXCLUDED_SONG_IDS` 와 동일한 의도 —
 * 무한정 커지지 않게 막되 PoC 한 세션의 스와이프 분량은 충분히 커버한다.
 */
export const MAX_SWIPE_REACTIONS = 200;

type SwipeReactionsState = {
  reactions: SwipeReactionEntry[];
  /** 곡 반응 기록 — 기존 반응이 있으면 최신 값으로 덮어쓴다. */
  recordReaction: (songId: number, reaction: SwipeReaction) => void;
  /** 특정 곡의 현재 반응 조회 (없으면 null). */
  reactionFor: (songId: number) => SwipeReaction | null;
  /** 전체 초기화. */
  clearReactions: () => void;
};

function mergeReaction(
  current: readonly SwipeReactionEntry[],
  songId: number,
  reaction: SwipeReaction,
): SwipeReactionEntry[] {
  // 기존 반응 제거 후 끝에 다시 push → 최신 반응이 항상 가장 뒤(최신)에 위치.
  const withoutSong = current.filter((entry) => entry.songId !== songId);
  withoutSong.push({ songId, reaction });
  if (withoutSong.length <= MAX_SWIPE_REACTIONS) {
    return withoutSong;
  }
  return withoutSong.slice(withoutSong.length - MAX_SWIPE_REACTIONS);
}

export const useSwipeReactionsStore = create<SwipeReactionsState>()(
  persist(
    (set, get) => ({
      reactions: [],
      recordReaction: (songId, reaction) => {
        set({ reactions: mergeReaction(get().reactions, songId, reaction) });
      },
      reactionFor: (songId) => {
        const entry = get().reactions.find((item) => item.songId === songId);
        return entry ? entry.reaction : null;
      },
      clearReactions: () => set({ reactions: [] }),
    }),
    {
      name: "mobruji-swipe-reactions",
      storage: createJSONStorage(() => localStorage),
      // #1105 회귀 가드: shape 변경 시 옛 localStorage 를 그대로 hydrate 하지 않도록
      // version 을 명시한다. migrate 는 v1 baseline passthrough — version bump 시 변환 hook.
      version: 1,
      migrate: (persisted) => persisted as SwipeReactionsState,
    },
  ),
);
