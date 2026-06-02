/**
 * swipeReactions store 테스트 (#1489).
 *
 * 검증 범위:
 *  - 반응 기록 + 곡당 1개(최신 덮어쓰기) 정책.
 *  - reactionFor 조회.
 *  - 상한(MAX_SWIPE_REACTIONS) 초과 시 가장 오래된 것부터 만료.
 *  - clearReactions.
 */

import { beforeEach, describe, expect, it } from "vitest";

import {
  MAX_SWIPE_REACTIONS,
  useSwipeReactionsStore,
} from "./swipeReactions";

beforeEach(() => {
  useSwipeReactionsStore.setState({ reactions: [] });
  if (typeof localStorage !== "undefined") {
    localStorage.removeItem("mobruji-swipe-reactions");
  }
});

describe("useSwipeReactionsStore", () => {
  it("반응을 기록하고 reactionFor 로 조회한다", () => {
    const { recordReaction, reactionFor } = useSwipeReactionsStore.getState();
    recordReaction(10, "like");
    recordReaction(20, "pass");

    expect(useSwipeReactionsStore.getState().reactionFor(10)).toBe("like");
    expect(useSwipeReactionsStore.getState().reactionFor(20)).toBe("pass");
    expect(reactionFor(999)).toBeNull();
  });

  it("같은 곡을 다시 기록하면 최신 반응으로 덮어쓴다 (곡당 1개)", () => {
    const { recordReaction } = useSwipeReactionsStore.getState();
    recordReaction(10, "pass");
    recordReaction(10, "like");

    const { reactions } = useSwipeReactionsStore.getState();
    expect(reactions).toHaveLength(1);
    expect(reactions[0]).toEqual({ songId: 10, reaction: "like" });
  });

  it("덮어쓰면 최신 반응이 가장 뒤(최신)에 위치한다", () => {
    const { recordReaction } = useSwipeReactionsStore.getState();
    recordReaction(1, "like");
    recordReaction(2, "like");
    recordReaction(1, "pass"); // 1 을 갱신 → 맨 뒤로 이동

    const ids = useSwipeReactionsStore
      .getState()
      .reactions.map((entry) => entry.songId);
    expect(ids).toEqual([2, 1]);
  });

  it("상한 초과 시 가장 오래된 반응부터 만료한다", () => {
    const { recordReaction } = useSwipeReactionsStore.getState();
    for (let songId = 1; songId <= MAX_SWIPE_REACTIONS + 5; songId += 1) {
      recordReaction(songId, "like");
    }

    const { reactions } = useSwipeReactionsStore.getState();
    expect(reactions).toHaveLength(MAX_SWIPE_REACTIONS);
    // 가장 오래된 1~5 가 잘려나가고 6 이 첫 항목.
    expect(reactions[0].songId).toBe(6);
    expect(reactions[reactions.length - 1].songId).toBe(MAX_SWIPE_REACTIONS + 5);
  });

  it("clearReactions 가 전체를 비운다", () => {
    const { recordReaction, clearReactions } =
      useSwipeReactionsStore.getState();
    recordReaction(1, "like");
    clearReactions();
    expect(useSwipeReactionsStore.getState().reactions).toEqual([]);
  });
});
