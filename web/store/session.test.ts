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

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { MAX_EXCLUDED_SONG_IDS, isValidSessionId, useSessionStore } from "./session";

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

/**
 * fallback entropy 가드 (closes #424).
 *
 * crypto.randomUUID 가 없는 환경(구형 브라우저)에서도 sessionId 가 RFC 4122 v4
 * UUID 형식(`xxxxxxxx-xxxx-4xxx-[89ab]xxx-xxxxxxxxxxxx`)으로 생성되는지 검증한다.
 * AS-IS `Math.random()` 기반 41-bit entropy 폐기 회귀를 막는다.
 */
describe("useSessionStore.ensureSessionId fallback (no crypto.randomUUID)", () => {
  const originalRandomUUID = globalThis.crypto?.randomUUID;

  afterEach(() => {
    // 다른 테스트로 leak 방지: spy 가 있으면 복원, 없으면 원본 재할당.
    vi.restoreAllMocks();
    if (originalRandomUUID && globalThis.crypto) {
      Object.defineProperty(globalThis.crypto, "randomUUID", {
        configurable: true,
        value: originalRandomUUID,
      });
    }
  });

  it("crypto.randomUUID 가 없으면 getRandomValues 기반 v4 UUID 를 생성한다", () => {
    // happy-dom 의 crypto.randomUUID 제거 → fallback 분기 진입.
    Object.defineProperty(globalThis.crypto, "randomUUID", {
      configurable: true,
      value: undefined,
    });

    const id = useSessionStore.getState().ensureSessionId();

    // RFC 4122 v4: 8-4-4-4-12 hex, version=4, variant=10xx (y ∈ {8,9,a,b}).
    const uuidV4 =
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
    expect(id).toMatch(uuidV4);
  });

  it("두 번 호출하면 서로 다른 v4 UUID 가 나온다 (entropy 살아 있음)", () => {
    Object.defineProperty(globalThis.crypto, "randomUUID", {
      configurable: true,
      value: undefined,
    });

    const first = useSessionStore.getState().ensureSessionId();
    // 같은 store 인스턴스에서는 멱등이므로 reset 후 재생성.
    useSessionStore.getState().reset();
    const second = useSessionStore.getState().ensureSessionId();

    expect(first).not.toBe(second);
  });
});

/**
 * Stale localStorage sessionId 회귀 가드 (closes #1105).
 *
 * 사고: PR #991 이 `RecommendationCreateRequest` / `LikeToggleRequest` /
 * `BookmarkToggleRequest` 의 `sessionId` 에 `@Pattern(UUID_V4)` 를 강제하면서
 * localStorage 에 영속된 legacy format sessionId 가 모두 400 을 유발.
 * `ensureSessionId()` 가 영속 값을 형식 검증 없이 그대로 반환하던 회귀.
 */
describe("useSessionStore.ensureSessionId stale localStorage 가드 (#1105)", () => {
  it("legacy `sess_<ts>_<rand>` prefix 가 영속돼 있으면 새 UUIDv4 로 재발급한다", () => {
    const legacy = "sess_lq7k3m_abc12345";
    useSessionStore.setState({ sessionId: legacy });
    expect(isValidSessionId(legacy)).toBe(false);

    const fresh = useSessionStore.getState().ensureSessionId();
    expect(fresh).not.toBe(legacy);
    expect(isValidSessionId(fresh)).toBe(true);
    expect(useSessionStore.getState().sessionId).toBe(fresh);
  });

  it("대문자 hex UUID (BE 소문자 regex 미통과) 도 새로 재발급한다", () => {
    const uppercase = "ABCDEF12-1234-4ABC-89DE-1234567890AB";
    useSessionStore.setState({ sessionId: uppercase });
    expect(isValidSessionId(uppercase)).toBe(false);

    const fresh = useSessionStore.getState().ensureSessionId();
    expect(fresh).not.toBe(uppercase);
    expect(isValidSessionId(fresh)).toBe(true);
  });

  it("이미 valid UUIDv4 (소문자 hex 8-4-4-4-12) 면 그대로 유지한다 (멱등)", () => {
    const valid = "abcdef12-1234-4abc-89de-1234567890ab";
    useSessionStore.setState({ sessionId: valid });
    expect(isValidSessionId(valid)).toBe(true);

    const returned = useSessionStore.getState().ensureSessionId();
    expect(returned).toBe(valid);
  });

  it("빈 문자열 / null / 공백 등 falsy 또는 invalid 값은 새로 발급한다", () => {
    for (const stale of ["", "   ", "not-a-uuid", "12345"]) {
      useSessionStore.setState({ sessionId: stale });
      const fresh = useSessionStore.getState().ensureSessionId();
      expect(isValidSessionId(fresh)).toBe(true);
      expect(fresh).not.toBe(stale);
    }
  });
});

/**
 * `crypto` 자체가 없는 환경의 마지막 보루 fallback 도 BE `@Pattern(UUID_V4)`
 * 호환 형식을 유지해야 함 (closes #1105 — AS-IS `sess_<ts>_<rand>` 폐기).
 */
describe("useSessionStore.ensureSessionId weak fallback (no crypto)", () => {
  const originalCrypto = globalThis.crypto;

  afterEach(() => {
    if (originalCrypto) {
      Object.defineProperty(globalThis, "crypto", {
        configurable: true,
        value: originalCrypto,
      });
    }
  });

  it("crypto 가 전혀 없어도 sessionId 는 UUIDv4 hex 8-4-4-4-12 형식이다", () => {
    // crypto 객체 자체 제거 → 마지막 보루 분기 진입.
    Object.defineProperty(globalThis, "crypto", {
      configurable: true,
      value: undefined,
    });

    const id = useSessionStore.getState().ensureSessionId();
    expect(isValidSessionId(id)).toBe(true);
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
