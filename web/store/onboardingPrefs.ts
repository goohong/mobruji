/**
 * 온보딩 취향 프로필 저장소 — 가입 후 선택형 온보딩 결과 영속 (closes #1814).
 *
 * 가입 직후 `/onboarding` 에서 고른 나이대·성별·분위기를 localStorage 에 영속해
 * `/recommend` 진입 시 추천 필터의 **기본값(pre-fill)** 으로 쓴다. 세 값 모두 선택형이라
 * null(미선택/스킵)을 허용하며, null 이면 추천 요청에서 해당 신호가 생략돼 기존 동작과 동일하다.
 *
 * - 성별은 추천 genderFit 필터(#1781, MALE/FEMALE) 입력값이다. 로그인 사용자는 가입 직후
 *   `updateProfile({ gender })` 로 BE 프로필에도 best-effort 반영한다(기기 간 유지). 본 store 는
 *   익명 흐름까지 포함한 추천 pre-fill 의 단일 소스다.
 * - 익명 [[session]] / [[auth]] store 와 독립 — 토큰이 없어도 추천 pre-fill 은 동작한다.
 */

"use client";

import { create } from "zustand";
import { persist, createJSONStorage } from "zustand/middleware";

import type { AgeGroup, Mood, RequestedGender } from "@/lib/api/recommendation";

type OnboardingPrefsState = {
  mood: Mood | null;
  ageGroup: AgeGroup | null;
  gender: RequestedGender | null;
  /**
   * 선택값을 영속한다. `undefined` 인 키는 기존 값을 유지하고, 명시 `null` 은 해제(스킵)다 —
   * 온보딩 화면이 세 필드를 한 번에 저장하므로 부분 갱신/스킵 둘 다 자연스럽게 표현한다.
   */
  setPrefs: (prefs: {
    mood?: Mood | null;
    ageGroup?: AgeGroup | null;
    gender?: RequestedGender | null;
  }) => void;
  reset: () => void;
};

export const useOnboardingPrefsStore = create<OnboardingPrefsState>()(
  persist(
    (set) => ({
      mood: null,
      ageGroup: null,
      gender: null,
      setPrefs: (prefs) =>
        set((state) => ({
          mood: prefs.mood !== undefined ? prefs.mood : state.mood,
          ageGroup: prefs.ageGroup !== undefined ? prefs.ageGroup : state.ageGroup,
          gender: prefs.gender !== undefined ? prefs.gender : state.gender,
        })),
      reset: () => set({ mood: null, ageGroup: null, gender: null }),
    }),
    {
      name: "mobruji-onboarding-prefs",
      storage: createJSONStorage(() => localStorage),
      // [[session]] / [[auth]] 와 동일 — version/migrate 로 스키마 회귀(#1105)를 막는다.
      version: 1,
      migrate: (persisted) => persisted as OnboardingPrefsState,
    },
  ),
);
