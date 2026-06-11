/**
 * 익명 세션 ID 저장소 (클라이언트 전용).
 *
 * - voice-range-input.md Q2 결정: PoC는 익명 세션. BE는 sessionId를 path/body로 받음.
 * - localStorage에 영속화해서 새로고침 후에도 유지.
 * - voiceRangeId는 "내 음역대 보기" 라우팅 및 **재추천 누적 reset 트리거**로 쓰인다.
 *
 * 재추천 누적 (closes #83 #84, 2026-05-21):
 *   - `excludedSongIds`는 "다른 곡 추천받기" 버튼이 누른 곡 ID들의 누적 집합.
 *   - 매 추천 응답마다 결과 곡 ID들을 push하고, 다음 호출 시 BE로 전달해
 *     같은 voiceRange에서 다른 결과를 끌어내도록 한다 (BE seed 입력 포함).
 *   - 중복은 자동 제거(Set 기반), **최대 100개**까지만 유지(초과 시 가장 오래된 ID부터 만료).
 *   - `setVoiceRangeId`가 다른 ID로 갈아끼우면 자동으로 누적 reset →
 *     새 사람/세션이 시작될 때 이전 사용자의 제외 목록을 끌고 가지 않는다.
 */

"use client";

import { create } from "zustand";
import { persist, createJSONStorage } from "zustand/middleware";

/**
 * 누적 제외 곡 ID 상한.
 *
 * spec 의도: 한 세션의 재추천 횟수는 제한 없이 누적되지만 페이로드/seed 입력이
 * 무한정 커지지는 않게 막아야 한다. v1 PoC 기준 100건이면 카드 6~8건 * 12~16회
 * 반복까지 커버한다. 초과 시 가장 오래된 ID부터 만료한다.
 */
export const MAX_EXCLUDED_SONG_IDS = 100;

type SessionState = {
  sessionId: string | null;
  voiceRangeId: number | null;
  excludedSongIds: number[];
  ensureSessionId: () => string;
  setVoiceRangeId: (id: number) => void;
  appendExcluded: (ids: number[]) => void;
  clearExcluded: () => void;
  reset: () => void;
};

/**
 * BE `SessionIdPatterns.UUID_V4` 와 일치해야 하는 형식 regex (소문자 hex 8-4-4-4-12).
 *
 * spec: ADR-0011 — client 가 발급한 UUIDv4 만 허용. BE `*Request` DTO 의
 * `@Pattern(SessionIdPatterns.UUID_V4)` (PR #991) 와 정확히 동일한 정규식을
 * FE 측에서도 적용하여 stale localStorage 의 legacy format (예: `sess_<ts>_<rand>`
 * fallback, 대문자 hex 등) 을 BE 호출 전 detect 한다.
 *
 * BE 와 마찬가지로 version/variant nibble 까지는 강제하지 않는다 — generator
 * 호환성을 위한 가벼운 형식 가드.
 */
const SESSION_ID_UUID_V4 =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/**
 * 영속된 sessionId 가 BE @Pattern(UUID_V4) 통과 가능한 형식인지 검증.
 *
 * stale localStorage sessionId (PR #991 이전 발급분, 구형 fallback prefix 등) 가
 * BE 400 회귀를 유발하던 경로를 막는 형식 가드. 호출 측은 false 시 새 ID 를
 * 발급하고 기존 값을 폐기해야 한다.
 */
export function isValidSessionId(value: unknown): value is string {
  return typeof value === "string" && SESSION_ID_UUID_V4.test(value);
}

/**
 * 익명 세션 ID 생성 (closes #424, #406 M1 결정).
 *
 * 우선순위:
 *   1. `crypto.randomUUID()` — 모던 브라우저(Safari 15.4+, Chrome 92+, Firefox 95+).
 *   2. `crypto.getRandomValues()` 기반 RFC 4122 v4 UUID — 구형 브라우저.
 *
 * AS-IS(폐기): `Math.random() + Date.now()` 조합은 약 41-bit entropy 로
 * 충돌·예측 가능성이 있어 fallback 분기에서도 충분치 않았다. v4 UUID 는
 * 122-bit entropy 를 보장한다 (RFC 4122 §4.4).
 *
 * 마지막 보루: `crypto` 자체가 없는 환경(Node SSR 등 일부 케이스)에서는
 * 결정성 깨진 약한 ID라도 반환해야 호출부가 죽지 않으므로 기존 prefix 포맷을
 * 유지한다. 실제 브라우저 런타임에서는 도달하지 않는 분기.
 */
function generateSessionId(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  if (
    typeof crypto !== "undefined" &&
    typeof crypto.getRandomValues === "function"
  ) {
    return randomUuidV4FromBytes();
  }
  // crypto 자체가 없는 마지막 보루 분기 — BE `@Pattern(UUID_V4)` (#991, 회귀 #1105)
  // 와 호환되려면 entropy 가 약하더라도 형식은 UUIDv4 hex 8-4-4-4-12 를 유지해야
  // 한다. 실 브라우저 런타임은 위 두 분기에서 종료되므로 본 분기는 SSR/Node
  // 일부 경로의 graceful degradation 전용. AS-IS `sess_<ts>_<rand>` prefix 는
  // BE 400 을 유발하므로 폐기.
  return weakHexUuidV4();
}

/**
 * `crypto` 가 없는 환경의 마지막 보루용 weak hex UUIDv4 발급.
 *
 * - entropy 는 `Math.random()` 기반이라 약하지만, BE 의 `@Pattern(UUID_V4)`
 *   regex (소문자 hex 8-4-4-4-12) 를 통과하는 형식은 보장한다.
 * - 실 브라우저는 `crypto.randomUUID()` / `getRandomValues()` 분기에서 끝나므로
 *   본 분기는 거의 도달하지 않는다.
 */
function weakHexUuidV4(): string {
  const hex = "0123456789abcdef";
  let out = "";
  for (let i = 0; i < 32; i += 1) {
    out += hex[Math.floor(Math.random() * 16)];
    if (i === 7 || i === 11 || i === 15 || i === 19) {
      out += "-";
    }
  }
  return out;
}

/**
 * `crypto.getRandomValues()` 16 byte 로 RFC 4122 v4 UUID 문자열을 만든다.
 *
 * - byte 6: 상위 4비트를 `0100` (v4) 로 고정.
 * - byte 8: 상위 2비트를 `10` (RFC 4122 variant) 로 고정.
 * - 결과 포맷: `xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx` (y ∈ {8,9,a,b}).
 *
 * `export` 인 이유: PR #769 fallback 패턴을 history store 의 `generateEntryId()` 가
 * 그대로 재사용한다 — sessionId / entry id 둘 다 RFC 4122 v4 보장이 필요해서다
 * (issue #422 후속, sessionId/requestId/entry-id 형식 일관성).
 */
export function randomUuidV4FromBytes(): string {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, "0"));
  return (
    `${hex[0]}${hex[1]}${hex[2]}${hex[3]}-` +
    `${hex[4]}${hex[5]}-` +
    `${hex[6]}${hex[7]}-` +
    `${hex[8]}${hex[9]}-` +
    `${hex[10]}${hex[11]}${hex[12]}${hex[13]}${hex[14]}${hex[15]}`
  );
}

/**
 * 기존 누적 리스트에 새 ID들을 덧붙여 정규화한다.
 *
 * - 중복은 가장 먼저 등장한 위치를 유지(Set + insertion order).
 * - 상한 초과 시 앞쪽(가장 오래된)부터 잘라낸다.
 * - 새 입력에 들어 있는 ID가 기존 리스트에도 있으면 위치를 옮기지 않는다 —
 *   "가장 오래된 ID부터 만료" 정책을 지키기 위해.
 */
function mergeExcluded(
  current: readonly number[],
  incoming: readonly number[],
): number[] {
  if (incoming.length === 0) {
    return current.slice();
  }
  const seen = new Set<number>(current);
  const merged = current.slice();
  for (const id of incoming) {
    if (seen.has(id)) {
      continue;
    }
    seen.add(id);
    merged.push(id);
  }
  if (merged.length <= MAX_EXCLUDED_SONG_IDS) {
    return merged;
  }
  return merged.slice(merged.length - MAX_EXCLUDED_SONG_IDS);
}

export const useSessionStore = create<SessionState>()(
  persist(
    (set, get) => ({
      sessionId: null,
      voiceRangeId: null,
      excludedSongIds: [],
      ensureSessionId: () => {
        const existing = get().sessionId;
        // BE `*Request.sessionId` @Pattern(UUID_V4) (PR #991) 와 형식이 다른
        // legacy / stale 값이 localStorage 에 영속돼 있으면 그대로 신뢰하지
        // 않고 새 ID 를 발급한다. 회귀 사고: 추천 받기 → 저장 400
        // (스코프: closes #1105 — \`sess_<ts>_<rand>\` 등 구형 fallback / 대문자
        // hex / 그 외 invalid format 모두 정정).
        if (isValidSessionId(existing)) {
          return existing;
        }
        const fresh = generateSessionId();
        set({ sessionId: fresh });
        return fresh;
      },
      setVoiceRangeId: (id) => {
        const previous = get().voiceRangeId;
        if (previous === id) {
          set({ voiceRangeId: id });
          return;
        }
        // 다른 음역대 ID로 갈아끼우면 누적 reset — 새 사람/세션이 시작될 때
        // 이전 사용자의 제외 목록을 끌고 가지 않도록 한다.
        set({ voiceRangeId: id, excludedSongIds: [] });
      },
      appendExcluded: (ids) => {
        if (ids.length === 0) {
          return;
        }
        set({ excludedSongIds: mergeExcluded(get().excludedSongIds, ids) });
      },
      clearExcluded: () => set({ excludedSongIds: [] }),
      reset: () =>
        set({ sessionId: null, voiceRangeId: null, excludedSongIds: [] }),
    }),
    {
      name: "mobruji-session",
      storage: createJSONStorage(() => localStorage),
      /**
       * persist hydration 시점에 legacy / invalid sessionId 를 폐기 (closes #1255 후속,
       * be PR #1255 GlobalExceptionHandler 회귀 가드).
       *
       * 사고: 사용자 device 의 localStorage 에 PR #991 이전 발급된 `sess_<ts>_<rand>`
       * 형식 sessionId 가 영속되어 있으면, `ensureSessionId()` 를 호출하지 않고
       * `state.sessionId` 만 직접 read 하는 page (예: home `ReturningUserPanel`,
       * `/recommend` 진입 query) 가 그대로 legacy ID 를 `readVoiceRange()` /
       * `createRecommendation()` 에 전달 → BE `@Pattern(UUID_V4)` 400.
       *
       * 해결: hydration 콜백에서 sessionId 형식을 직접 검증하고, invalid 면 sessionId +
       * voiceRangeId 동시 null 화. voiceRangeId 도 클리어하는 이유는 voice_range row 가
       * 기존 legacy sessionId 에 bound 되어 있어 새 sessionId 로 BE 조회 시 401/404 가
       * 나오기 때문 — `NoSessionFallback` UX 가 자동으로 음역대 재등록 prompt 를
       * 표시하도록 한다 (사용자 데이터 손실 0 — voice_range 입력 한 번 다시).
       *
       * excludedSongIds 도 같이 비운다 — 다른 사용자 세션의 누적 목록을 끌고 가지 않도록.
       */
      onRehydrateStorage: () => (state) => {
        if (!state) {
          return;
        }
        if (state.sessionId !== null && !isValidSessionId(state.sessionId)) {
          state.sessionId = null;
          state.voiceRangeId = null;
          state.excludedSongIds = [];
        }
      },
    },
  ),
);
