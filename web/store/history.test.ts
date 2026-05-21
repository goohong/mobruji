/**
 * 히스토리 스토어 단위 테스트 (closes #134).
 *
 * 범위:
 *  - appendRecommendation: 신규 항목 누적 + 자동 id/requestedAt 발급.
 *  - 20건 상한: 초과 시 가장 오래된 항목 만료(prepend FIFO).
 *  - removeRecommendation: id 매칭 항목만 제거.
 *  - clearHistory: 전체 초기화.
 *  - persist 라운드트립: JSON 직렬/역직렬 후 같은 데이터 유지.
 */

import { beforeEach, describe, expect, it } from "vitest";

import type { RecommendedSongResponse } from "@/lib/api/recommendation";

import {
  MAX_HISTORY_ENTRIES,
  useHistoryStore,
  type RecommendationHistoryInput,
} from "./history";

function buildSong(id: number): RecommendedSongResponse {
  return {
    rankPosition: 1,
    score: 0.9,
    matchReason: "음역 매칭",
    song: {
      id,
      title: `곡-${id}`,
      artist: `가수-${id}`,
      releaseYear: 2024,
      keyOriginal: "C_MAJOR",
      bpm: 110,
      mood: "UPBEAT",
      language: "ko",
      genre: "POP",
      tjNumber: `T-${id}`,
      kyNumber: `K-${id}`,
      metadataSource: "MANUAL_SEED",
    },
  };
}

function buildInput(requestId: number): RecommendationHistoryInput {
  return {
    requestId,
    voiceRangeId: 42,
    songs: [buildSong(requestId * 10), buildSong(requestId * 10 + 1)],
    excludedSongIds: [1, 2],
  };
}

beforeEach(() => {
  // persist middleware 격리 — 매 테스트마다 초기 상태로 강제 복원.
  useHistoryStore.setState({ recommendations: [] });
  // localStorage도 비워 라운드트립 테스트 간섭 방지.
  if (typeof localStorage !== "undefined") {
    localStorage.removeItem("mobruji-history");
  }
});

describe("useHistoryStore.appendRecommendation", () => {
  it("새 항목을 prepend 하고 자동으로 id/requestedAt을 발급한다", () => {
    const { appendRecommendation } = useHistoryStore.getState();

    appendRecommendation(buildInput(1));
    appendRecommendation(buildInput(2));

    const list = useHistoryStore.getState().recommendations;
    expect(list).toHaveLength(2);
    // 최신이 앞 — requestId 2 가 먼저.
    expect(list[0].requestId).toBe(2);
    expect(list[1].requestId).toBe(1);
    // id 는 자동 발급되어 unique.
    expect(list[0].id).toBeTruthy();
    expect(list[1].id).toBeTruthy();
    expect(list[0].id).not.toBe(list[1].id);
    // requestedAt 은 ISO8601 — Date.parse 가 NaN 이 아니어야.
    expect(Number.isNaN(Date.parse(list[0].requestedAt))).toBe(false);
    expect(Number.isNaN(Date.parse(list[1].requestedAt))).toBe(false);
  });

  it("MAX(20) 초과 시 가장 오래된 항목부터 만료한다", () => {
    const { appendRecommendation } = useHistoryStore.getState();

    // 1..MAX 까지 누적 — 정확히 상한.
    for (let i = 1; i <= MAX_HISTORY_ENTRIES; i++) {
      appendRecommendation(buildInput(i));
    }
    expect(useHistoryStore.getState().recommendations).toHaveLength(
      MAX_HISTORY_ENTRIES,
    );
    // 가장 오래된 = requestId 1, 가장 최근 = requestId MAX.
    expect(useHistoryStore.getState().recommendations[0].requestId).toBe(
      MAX_HISTORY_ENTRIES,
    );
    expect(
      useHistoryStore.getState().recommendations[MAX_HISTORY_ENTRIES - 1]
        .requestId,
    ).toBe(1);

    // 5건 더 → 앞쪽(가장 오래된) 5개가 만료된다.
    for (let i = MAX_HISTORY_ENTRIES + 1; i <= MAX_HISTORY_ENTRIES + 5; i++) {
      appendRecommendation(buildInput(i));
    }
    const after = useHistoryStore.getState().recommendations;
    expect(after).toHaveLength(MAX_HISTORY_ENTRIES);
    // 새로 들어온 5개는 모두 살아 있고, 가장 오래된 1..5 는 만료.
    const requestIds = after.map((entry) => entry.requestId);
    expect(requestIds).toContain(MAX_HISTORY_ENTRIES + 5);
    expect(requestIds).toContain(MAX_HISTORY_ENTRIES + 1);
    expect(requestIds).not.toContain(1);
    expect(requestIds).not.toContain(5);
    expect(requestIds).toContain(6);
  });
});

describe("useHistoryStore.removeRecommendation", () => {
  it("id에 해당하는 항목만 제거한다", () => {
    const { appendRecommendation } = useHistoryStore.getState();
    appendRecommendation(buildInput(1));
    appendRecommendation(buildInput(2));
    appendRecommendation(buildInput(3));

    const targetId = useHistoryStore.getState().recommendations[1].id; // 가운데 = requestId 2

    useHistoryStore.getState().removeRecommendation(targetId);

    const after = useHistoryStore.getState().recommendations;
    expect(after).toHaveLength(2);
    expect(after.map((entry) => entry.requestId)).toEqual([3, 1]);
  });
});

describe("useHistoryStore.clearHistory", () => {
  it("전체 히스토리를 비운다", () => {
    const { appendRecommendation, clearHistory } = useHistoryStore.getState();
    appendRecommendation(buildInput(1));
    appendRecommendation(buildInput(2));

    clearHistory();
    expect(useHistoryStore.getState().recommendations).toEqual([]);
  });
});

describe("useHistoryStore persist 라운드트립", () => {
  it("localStorage 에 직렬화된 형태로 저장되며 JSON 라운드트립 시 동일 데이터를 보존한다", () => {
    const { appendRecommendation } = useHistoryStore.getState();
    appendRecommendation(buildInput(7));
    appendRecommendation(buildInput(8));

    const before = useHistoryStore.getState().recommendations;
    expect(before).toHaveLength(2);

    // zustand persist 가 localStorage 에 실제로 저장했는지 검증.
    const raw = localStorage.getItem("mobruji-history");
    expect(raw).not.toBeNull();

    const parsed = JSON.parse(raw as string) as {
      state: { recommendations: typeof before };
    };
    expect(parsed.state.recommendations).toHaveLength(2);
    // 직렬화 순서: 최신 prepend 정책에 따라 requestId 8 이 먼저.
    expect(parsed.state.recommendations[0].requestId).toBe(8);
    expect(parsed.state.recommendations[1].requestId).toBe(7);
    // id / requestedAt 도 그대로 직렬화되어 있어야.
    expect(parsed.state.recommendations[0].id).toBe(before[0].id);
    expect(parsed.state.recommendations[0].requestedAt).toBe(
      before[0].requestedAt,
    );
    // 곡 메타데이터(songs)도 깊은 비교로 동일.
    expect(parsed.state.recommendations[0].songs).toEqual(before[0].songs);
    // excludedSongIds 스냅샷도 유지.
    expect(parsed.state.recommendations[0].excludedSongIds).toEqual(
      before[0].excludedSongIds,
    );
  });
});
