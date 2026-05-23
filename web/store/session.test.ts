/**
 * 세션 스토어 단위 테스트 (closes #83 #84).
 *
 * 범위:
 *  - excludedSongIds 누적: Set 동작(중복 자동 제거).
 *  - 100개 상한: 초과 시 가장 오래된 ID부터 만료.
 *  - voiceRangeId 변경 시 누적 자동 clear.
 *  - 같은 voiceRangeId 재설정은 누적을 유지.
 *  - clearExcluded / reset 동작.
 *
 * 구현: persist middleware가 localStorage를 사용하므로 vitest happy-dom 환경에서도
 * 안전하게 동작한다. 각 테스트는 store를 `setState`로 명시 reset 해서 격리한다.
 */

import { beforeEach, describe, expect, it } from "vitest";

import { MAX_EXCLUDED_SONG_IDS, useSessionStore } from "./session";

beforeEach(() => {
  // persist middleware에 묶여 있어도 setState로 초기 상태 강제 복원 가능.
  // localStorage도 비워야 ensureSessionId persist 테스트가 격리된다.
  if (typeof localStorage !== "undefined") {
    localStorage.clear();
  }
  useSessionStore.setState({
    sessionId: null,
    voiceRangeId: null,
    excludedSongIds: [],
  });
});

describe("useSessionStore.appendExcluded", () => {
  it("새 ID들을 누적하고 중복은 자동 제거한다 (Set 동작)", () => {
    const { appendExcluded } = useSessionStore.getState();

    appendExcluded([1, 2, 3]);
    expect(useSessionStore.getState().excludedSongIds).toEqual([1, 2, 3]);

    // 일부 겹치고 새 ID 추가 — 기존 위치는 그대로, 새 ID만 뒤에 붙는다.
    appendExcluded([2, 3, 4, 5]);
    expect(useSessionStore.getState().excludedSongIds).toEqual([
      1, 2, 3, 4, 5,
    ]);

    // 같은 호출 안의 중복도 한 번만 들어간다.
    appendExcluded([6, 6, 7, 7, 5]);
    expect(useSessionStore.getState().excludedSongIds).toEqual([
      1, 2, 3, 4, 5, 6, 7,
    ]);
  });

  it("빈 입력은 no-op 이다", () => {
    const { appendExcluded } = useSessionStore.getState();
    appendExcluded([10, 11]);
    appendExcluded([]);
    expect(useSessionStore.getState().excludedSongIds).toEqual([10, 11]);
  });

  it("MAX(100) 초과 시 가장 오래된 ID부터 만료한다", () => {
    const { appendExcluded } = useSessionStore.getState();

    // 1..100 까지 누적 → 정확히 상한.
    const initial = Array.from({ length: MAX_EXCLUDED_SONG_IDS }, (_, i) => i + 1);
    appendExcluded(initial);
    expect(useSessionStore.getState().excludedSongIds).toHaveLength(
      MAX_EXCLUDED_SONG_IDS,
    );
    expect(useSessionStore.getState().excludedSongIds[0]).toBe(1);

    // 5개 더 → 앞쪽 5개 만료, 뒤에 새 ID들 추가.
    appendExcluded([101, 102, 103, 104, 105]);
    const after = useSessionStore.getState().excludedSongIds;
    expect(after).toHaveLength(MAX_EXCLUDED_SONG_IDS);
    // 가장 오래된 1..5는 만료되어야 한다.
    expect(after[0]).toBe(6);
    expect(after[after.length - 1]).toBe(105);
    expect(after).not.toContain(1);
    expect(after).not.toContain(5);
    expect(after).toContain(105);
  });
});

describe("useSessionStore.setVoiceRangeId", () => {
  it("다른 voiceRangeId로 갈아끼우면 excludedSongIds를 자동 clear 한다", () => {
    const { appendExcluded, setVoiceRangeId } = useSessionStore.getState();

    setVoiceRangeId(1);
    appendExcluded([10, 20, 30]);
    expect(useSessionStore.getState().excludedSongIds).toEqual([10, 20, 30]);

    setVoiceRangeId(2);
    expect(useSessionStore.getState().voiceRangeId).toBe(2);
    expect(useSessionStore.getState().excludedSongIds).toEqual([]);
  });

  it("같은 voiceRangeId 재설정은 excludedSongIds를 유지한다", () => {
    const { appendExcluded, setVoiceRangeId } = useSessionStore.getState();

    setVoiceRangeId(7);
    appendExcluded([1, 2, 3]);
    setVoiceRangeId(7);
    expect(useSessionStore.getState().excludedSongIds).toEqual([1, 2, 3]);
  });
});

describe("useSessionStore.ensureSessionId", () => {
  it("최초 호출 시 sessionId를 새로 생성하고 이후 호출은 같은 값을 반환한다 (멱등)", () => {
    const first = useSessionStore.getState().ensureSessionId();
    expect(first).toBeTruthy();
    expect(first.length).toBeGreaterThan(0);
    expect(useSessionStore.getState().sessionId).toBe(first);

    const second = useSessionStore.getState().ensureSessionId();
    expect(second).toBe(first);
  });

  it("reset 호출 후 ensureSessionId는 새 값을 만든다 — 이전 ID를 끌고 가지 않는다", () => {
    const before = useSessionStore.getState().ensureSessionId();
    useSessionStore.getState().reset();
    expect(useSessionStore.getState().sessionId).toBeNull();
    const after = useSessionStore.getState().ensureSessionId();
    expect(after).not.toBe(before);
  });

  it("persist storage key 는 'mobruji-session' 으로 sessionId를 직렬화한다", () => {
    const id = useSessionStore.getState().ensureSessionId();
    const raw = localStorage.getItem("mobruji-session");
    expect(raw).not.toBeNull();
    // zustand persist payload: { state: { sessionId, ... }, version }
    expect(raw).toContain(id);
  });
});

describe("useSessionStore.clearExcluded / reset", () => {
  it("clearExcluded 호출 시 누적 리스트만 비운다 (sessionId 보존)", () => {
    useSessionStore.setState({ sessionId: "abc" });
    const { appendExcluded, clearExcluded } = useSessionStore.getState();
    appendExcluded([1, 2, 3]);
    clearExcluded();
    expect(useSessionStore.getState().excludedSongIds).toEqual([]);
    expect(useSessionStore.getState().sessionId).toBe("abc");
  });

  it("reset 호출 시 sessionId, voiceRangeId, excludedSongIds 모두 초기화한다", () => {
    useSessionStore.setState({
      sessionId: "abc",
      voiceRangeId: 9,
      excludedSongIds: [1, 2, 3],
    });
    useSessionStore.getState().reset();
    const state = useSessionStore.getState();
    expect(state.sessionId).toBeNull();
    expect(state.voiceRangeId).toBeNull();
    expect(state.excludedSongIds).toEqual([]);
  });
});
