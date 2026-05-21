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

function generateSessionId(): string {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return crypto.randomUUID();
  }
  return `sess_${Date.now().toString(36)}_${Math.random()
    .toString(36)
    .slice(2, 10)}`;
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
        if (existing) {
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
    },
  ),
);
