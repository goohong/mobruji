/**
 * 익명 세션 ID 저장소 (클라이언트 전용).
 *
 * - voice-range-input.md Q2 결정: PoC는 익명 세션. BE는 sessionId를 path/body로 받음.
 * - localStorage에 영속화해서 새로고침 후에도 유지.
 * - voiceRangeId는 현재 사용처는 없지만, 향후 "내 음역대 보기" 라우팅 등에 쓰일 가능성을 열어둠.
 */

"use client";

import { create } from "zustand";
import { persist, createJSONStorage } from "zustand/middleware";

type SessionState = {
  sessionId: string | null;
  voiceRangeId: number | null;
  ensureSessionId: () => string;
  setVoiceRangeId: (id: number) => void;
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

export const useSessionStore = create<SessionState>()(
  persist(
    (set, get) => ({
      sessionId: null,
      voiceRangeId: null,
      ensureSessionId: () => {
        const existing = get().sessionId;
        if (existing) {
          return existing;
        }
        const fresh = generateSessionId();
        set({ sessionId: fresh });
        return fresh;
      },
      setVoiceRangeId: (id) => set({ voiceRangeId: id }),
      reset: () => set({ sessionId: null, voiceRangeId: null }),
    }),
    {
      name: "mobruji-session",
      storage: createJSONStorage(() => localStorage),
    },
  ),
);
