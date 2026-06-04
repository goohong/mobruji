/**
 * 추천 기본값 저장소 (closes #1814) — 회원가입 온보딩에서 고른 나이대·성별·취향을
 * 클라이언트에 영속해 `/recommend` 진입 시 필터 기본값으로 pre-fill 한다.
 *
 * 배경/정책:
 *   - 추천 API 는 이미 `ageGroup`(#1487 generationFit)·`mood`·`gender`(#1781 genderFit)
 *     입력을 받지만, 가입자가 매번 필터를 다시 고르지 않도록 온보딩 선택값을 기억한다.
 *   - 성별은 BE `UserProfileResponse`/`UpdateProfileRequest` 에 영속 필드가 있어 가입 후
 *     `updateProfile` 로 백엔드 프로필에도 반영한다. 나이대·취향은 현재 BE 프로필에
 *     대응 필드가 없어 이 클라이언트 store 만 권위 저장소다(하위호환 — 미선택이면 생략).
 *   - 각 항목은 **선택**이라 null 을 허용한다(스킵 = 기본값 없음 = 종전 동작 유지).
 *   - 음성·PII 는 담지 않는다(04-security-policy.md §3). 성별은 추천 가중 입력일 뿐이며
 *     BE 프로필에 이미 영속되는 값이라 클라이언트 미러도 동일 범위다.
 *
 * [[onboarding]] 와는 별개 — 그 store 는 페르소나 진입 경로·진행 단계를 추적하고,
 * 이 store 는 추천 필터의 영속 기본값만 담는다(라이프사이클이 다르다).
 */

"use client";

import { create } from "zustand";
import { createJSONStorage, persist } from "zustand/middleware";

import type { AgeGroup, Mood } from "@/lib/api/recommendation";
import type { UserGender } from "@/lib/api/auth";

/** 온보딩에서 고른 추천 기본값. 각 항목은 미선택(스킵) 시 null. */
export type RecommendDefaults = {
  ageGroup: AgeGroup | null;
  gender: UserGender | null;
  mood: Mood | null;
};

type RecommendDefaultsState = RecommendDefaults & {
  /** 온보딩 완료/스킵 여부 — 가입 후 온보딩을 한 번 거쳤는지 추적(중복 노출 방지). */
  onboardingCompleted: boolean;
  /** 온보딩 제출(완료/스킵) — 고른 값(일부 null 허용)을 한 번에 저장하고 완료로 전이. */
  applyOnboarding: (defaults: RecommendDefaults) => void;
  /** 전체 초기화(로그아웃/디버그). */
  reset: () => void;
};

const INITIAL_DEFAULTS: RecommendDefaults = {
  ageGroup: null,
  gender: null,
  mood: null,
};

export const useRecommendDefaultsStore = create<RecommendDefaultsState>()(
  persist(
    (set) => ({
      ...INITIAL_DEFAULTS,
      onboardingCompleted: false,
      applyOnboarding: (defaults) =>
        set({ ...defaults, onboardingCompleted: true }),
      reset: () => set({ ...INITIAL_DEFAULTS, onboardingCompleted: false }),
    }),
    {
      name: "mobruji-recommend-defaults",
      storage: createJSONStorage(() => localStorage),
      // [[onboarding]]/[[auth]] 와 동일 — version/migrate 로 스키마 회귀(#1105)를 막는다.
      version: 1,
      migrate: (persisted) => persisted as RecommendDefaultsState,
    },
  ),
);
