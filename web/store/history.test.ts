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

import { afterEach, beforeEach, describe, expect, it } from "vitest";

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

/**
 * 테스트용 requestId — issue #422 후 UUIDv7 문자열로 통일.
 *
 * 정렬/포함 검증을 위해 결정적이고 lexicographic 정렬 = 입력 순서가 되도록
 * 시드 정수를 padStart 해서 마지막 12 hex 자리에 박는다. v7 의 timestamp prefix
 * 위치(첫 12 hex)에 동일 값을 두면 prepend 정책의 의미가 흐려지므로,
 * 마지막 그룹(node id 12 hex)에 박아 단순 lexicographic 정렬은 의미 없음을
 * 명시한다 — 본 store 는 push 순서를 기준으로 prepend 한다.
 */
function rid(seed: number): string {
  const hex = seed.toString(16).padStart(12, "0");
  return `01933b1c-7f8a-7c2d-9b3e-${hex}`;
}

function buildInput(seed: number): RecommendationHistoryInput {
  return {
    requestId: rid(seed),
    voiceRangeId: 42,
    songs: [buildSong(seed * 10), buildSong(seed * 10 + 1)],
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
    expect(list[0].requestId).toBe(rid(2));
    expect(list[1].requestId).toBe(rid(1));
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
      rid(MAX_HISTORY_ENTRIES),
    );
    expect(
      useHistoryStore.getState().recommendations[MAX_HISTORY_ENTRIES - 1]
        .requestId,
    ).toBe(rid(1));

    // 5건 더 → 앞쪽(가장 오래된) 5개가 만료된다.
    for (let i = MAX_HISTORY_ENTRIES + 1; i <= MAX_HISTORY_ENTRIES + 5; i++) {
      appendRecommendation(buildInput(i));
    }
    const after = useHistoryStore.getState().recommendations;
    expect(after).toHaveLength(MAX_HISTORY_ENTRIES);
    // 새로 들어온 5개는 모두 살아 있고, 가장 오래된 1..5 는 만료.
    const requestIds = after.map((entry) => entry.requestId);
    expect(requestIds).toContain(rid(MAX_HISTORY_ENTRIES + 5));
    expect(requestIds).toContain(rid(MAX_HISTORY_ENTRIES + 1));
    expect(requestIds).not.toContain(rid(1));
    expect(requestIds).not.toContain(rid(5));
    expect(requestIds).toContain(rid(6));
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
    expect(after.map((entry) => entry.requestId)).toEqual([rid(3), rid(1)]);
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

describe("useHistoryStore 경계 회귀 가드 (#592)", () => {
  it("같은 requestId 를 여러 번 push 해도 dedupe 하지 않는다 (현 동작 명시)", () => {
    // store-side ID 가 충돌 방지용 — 향후 dedupe 정책 도입 시 본 테스트가 먼저 깨져야.
    const { appendRecommendation } = useHistoryStore.getState();
    appendRecommendation(buildInput(7));
    appendRecommendation(buildInput(7));
    const list = useHistoryStore.getState().recommendations;
    expect(list).toHaveLength(2);
    expect(list[0].id).not.toBe(list[1].id);
  });

  it("removeRecommendation 에 존재하지 않는 id 를 줘도 상태가 변하지 않는다", () => {
    const { appendRecommendation, removeRecommendation } =
      useHistoryStore.getState();
    appendRecommendation(buildInput(1));
    const before = useHistoryStore.getState().recommendations;
    removeRecommendation("does-not-exist");
    expect(useHistoryStore.getState().recommendations).toEqual(before);
  });

  it("clearHistory 후에도 새 항목을 정상 append 할 수 있다", () => {
    const { appendRecommendation, clearHistory } = useHistoryStore.getState();
    appendRecommendation(buildInput(1));
    clearHistory();
    appendRecommendation(buildInput(2));
    const list = useHistoryStore.getState().recommendations;
    expect(list).toHaveLength(1);
    expect(list[0].requestId).toBe(rid(2));
  });

  it("localStorage 가 손상되어도 메모리 상태 접근에 예외를 던지지 않는다", () => {
    // persist hydrate 는 모듈 로드 시 끝났으므로 손상된 raw 가 후속 흐름을 깨면 안 됨.
    localStorage.setItem("mobruji-history", "{not json");
    expect(() => useHistoryStore.getState().recommendations).not.toThrow();
    const { appendRecommendation } = useHistoryStore.getState();
    expect(() => appendRecommendation(buildInput(9))).not.toThrow();
    expect(useHistoryStore.getState().recommendations).toHaveLength(1);
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
    expect(parsed.state.recommendations[0].requestId).toBe(rid(8));
    expect(parsed.state.recommendations[1].requestId).toBe(rid(7));
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

/**
 * issue #422 후속 — requestId 타입(string) 회귀 + entry id fallback (PR #769 패턴).
 *
 * BE UUIDv7 전환과 일관되게 entry id 도 RFC 4122 v4 형식이 보장돼야 한다.
 * sessionId fallback 테스트(store/session.test.ts)와 동일한 happy-dom crypto
 * 무력화 패턴을 사용한다.
 */
describe("useHistoryStore — requestId 타입 + entry id fallback (#422)", () => {
  const UUID_V4_PATTERN =
    /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

  it("entry.requestId 는 fixture 가 넣어 준 string(UUID) 형식 그대로 보존된다", () => {
    const { appendRecommendation } = useHistoryStore.getState();
    appendRecommendation(buildInput(123));

    const list = useHistoryStore.getState().recommendations;
    expect(typeof list[0].requestId).toBe("string");
    expect(list[0].requestId).toBe(rid(123));
  });

  describe("generateEntryId fallback (no crypto.randomUUID)", () => {
    const originalRandomUUID = globalThis.crypto?.randomUUID;

    afterEach(() => {
      if (globalThis.crypto && originalRandomUUID) {
        Object.defineProperty(globalThis.crypto, "randomUUID", {
          value: originalRandomUUID,
          configurable: true,
        });
      }
    });

    it("crypto.randomUUID 가 없으면 getRandomValues 기반 v4 UUID 를 entry id 로 발급한다", () => {
      // happy-dom 의 crypto.randomUUID 제거 → fallback 분기 진입.
      Object.defineProperty(globalThis.crypto, "randomUUID", {
        value: undefined,
        configurable: true,
      });

      const { appendRecommendation } = useHistoryStore.getState();
      appendRecommendation(buildInput(1));

      const list = useHistoryStore.getState().recommendations;
      expect(list[0].id).toMatch(UUID_V4_PATTERN);
      // 충돌 회피 — 두 번째 push 도 다른 v4 UUID.
      appendRecommendation(buildInput(2));
      const after = useHistoryStore.getState().recommendations;
      expect(after[0].id).toMatch(UUID_V4_PATTERN);
      expect(after[0].id).not.toBe(after[1].id);
    });
  });
});
