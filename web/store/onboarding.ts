/**
 * 온보딩 상태 저장소 (클라이언트 전용, first-user-onboarding-flow.md §5-6).
 *
 * 역할:
 *   - 첫 진입 사용자가 고른 페르소나 진입 경로(`PersonaEntryPath`)와 진행 단계를
 *     localStorage 에 영속해 **중간 이탈 후 재진입 시 이어서 안내**할 수 있게 한다
 *     (spec §2 시나리오 5).
 *   - 온보딩 완료 기준은 **첫 추천 도달**이며, 도달 시점에 `completedAt` 을 찍어
 *     상태를 완료로 전이한다 (이전: voiceRangeId 존재로만 암묵 추론).
 *
 * 정책/보안:
 *   - 신규 BE 엔티티/endpoint 없음. 의도/단계만 보관하며 음성·PII 는 담지 않는다
 *     (spec §4 비기능, 04-security-policy.md §3).
 *   - 세션 음역대(`useSessionStore.voiceRangeId`) 와는 별개의 영속 key 로 둔다.
 *     음역대는 BE 와 연동되는 측정 결과, 온보딩 상태는 순수 클라이언트 진행 추적이라
 *     라이프사이클이 다르다.
 */

"use client";

import { create } from "zustand";
import { createJSONStorage, persist } from "zustand/middleware";

/**
 * 페르소나 진입 경로 (06-domain-model.md §4-1 등재).
 *
 * - `BEGINNER` (P-C 입문) — "내 목소리부터 알아보기", 가이드 측정(F1)으로 연결.
 * - `PRACTICE` (P-A 연습) — "발성·고음 연습할 곡", 연습 모드(F2)로 연결.
 * - `MOOD` (P-B 분위기) — "분위기 띄울 곡", 분위기 모드(F3)로 연결.
 *
 * "그냥 둘러보기"(보조 경로)는 의도 선택을 강제하지 않는 기존 단선 흐름이라
 * 페르소나가 아니다 → `entryPath` 는 `null` 로 두고 단계만 진행한다.
 */
export type PersonaEntryPath = "BEGINNER" | "PRACTICE" | "MOOD";

/**
 * 온보딩 진행 단계 (spec §5-4 시퀀스).
 *
 * - `INTENT` — 진입 분기(아직 경로 미선택, 초기값).
 * - `MEASURE` — 음역대 측정 단계로 진입.
 * - `RECOMMEND` — 첫 추천 도달 직전(추천 조건/호출).
 * - `DONE` — 첫 추천 도달(완료).
 */
export type OnboardingStep = "INTENT" | "MEASURE" | "RECOMMEND" | "DONE";

type OnboardingState = {
  /** 선택한 페르소나 경로. 둘러보기/미선택이면 null. */
  entryPath: PersonaEntryPath | null;
  /** 현재 진행 단계. */
  step: OnboardingStep;
  /** 첫 추천 도달(완료) 시각(epoch ms). 미완료면 null. */
  completedAt: number | null;
  /** 페르소나 카드 선택 — 경로 기록 + 측정 단계로 전이. */
  selectEntryPath: (path: PersonaEntryPath) => void;
  /** 둘러보기(보조 경로) 선택 — 경로 없이 측정 단계로 전이. */
  selectBrowse: () => void;
  /** 단계 직접 전이(자식 경로 wiring 에서 호출). */
  setStep: (step: OnboardingStep) => void;
  /** 첫 추천 도달 — 완료 단계 전이 + completedAt 기록. */
  complete: () => void;
  /** 전체 초기화(새 사용자/디버그). */
  reset: () => void;
};

export const useOnboardingStore = create<OnboardingState>()(
  persist(
    (set) => ({
      entryPath: null,
      step: "INTENT",
      completedAt: null,
      selectEntryPath: (path) =>
        set({ entryPath: path, step: "MEASURE", completedAt: null }),
      selectBrowse: () =>
        set({ entryPath: null, step: "MEASURE", completedAt: null }),
      setStep: (step) => set({ step }),
      complete: () => set({ step: "DONE", completedAt: Date.now() }),
      reset: () => set({ entryPath: null, step: "INTENT", completedAt: null }),
    }),
    {
      name: "mobruji-onboarding",
      storage: createJSONStorage(() => localStorage),
    },
  ),
);
