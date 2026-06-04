/**
 * 추천 기본값 store 테스트 (closes #1814).
 *
 * 검증:
 *  - 초기 상태는 전부 null + onboardingCompleted=false.
 *  - applyOnboarding 은 고른 값(일부 null 허용)을 저장하고 완료로 전이한다.
 *  - reset 은 초기 상태로 되돌린다.
 *  - localStorage 영속(재방문 시 pre-fill 유지).
 */

import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { useRecommendDefaultsStore } from "./recommendDefaults";

function resetStore() {
  useRecommendDefaultsStore.getState().reset();
  if (typeof localStorage !== "undefined") {
    localStorage.removeItem("mobruji-recommend-defaults");
  }
}

beforeEach(() => resetStore());
afterEach(() => resetStore());

describe("useRecommendDefaultsStore", () => {
  it("초기 상태는 전부 null + onboardingCompleted=false", () => {
    const state = useRecommendDefaultsStore.getState();
    expect(state.ageGroup).toBeNull();
    expect(state.gender).toBeNull();
    expect(state.mood).toBeNull();
    expect(state.onboardingCompleted).toBe(false);
  });

  it("applyOnboarding 은 고른 값을 저장하고 완료로 전이한다", () => {
    useRecommendDefaultsStore.getState().applyOnboarding({
      ageGroup: "TWENTIES",
      gender: "FEMALE",
      mood: "UPBEAT",
    });
    const state = useRecommendDefaultsStore.getState();
    expect(state.ageGroup).toBe("TWENTIES");
    expect(state.gender).toBe("FEMALE");
    expect(state.mood).toBe("UPBEAT");
    expect(state.onboardingCompleted).toBe(true);
  });

  it("일부만 고르고 나머지는 스킵(null)해도 그대로 저장한다", () => {
    useRecommendDefaultsStore.getState().applyOnboarding({
      ageGroup: "THIRTIES",
      gender: null,
      mood: null,
    });
    const state = useRecommendDefaultsStore.getState();
    expect(state.ageGroup).toBe("THIRTIES");
    expect(state.gender).toBeNull();
    expect(state.mood).toBeNull();
    expect(state.onboardingCompleted).toBe(true);
  });

  it("전부 스킵(모두 null)해도 완료로 전이한다", () => {
    useRecommendDefaultsStore
      .getState()
      .applyOnboarding({ ageGroup: null, gender: null, mood: null });
    const state = useRecommendDefaultsStore.getState();
    expect(state.ageGroup).toBeNull();
    expect(state.gender).toBeNull();
    expect(state.mood).toBeNull();
    expect(state.onboardingCompleted).toBe(true);
  });

  it("reset 은 초기 상태로 되돌린다", () => {
    useRecommendDefaultsStore.getState().applyOnboarding({
      ageGroup: "TEENS",
      gender: "MALE",
      mood: "CALM",
    });
    useRecommendDefaultsStore.getState().reset();
    const state = useRecommendDefaultsStore.getState();
    expect(state.ageGroup).toBeNull();
    expect(state.gender).toBeNull();
    expect(state.mood).toBeNull();
    expect(state.onboardingCompleted).toBe(false);
  });

  it("선택 값이 localStorage 에 영속되어 재방문 시 유지된다", () => {
    useRecommendDefaultsStore.getState().applyOnboarding({
      ageGroup: "FORTIES",
      gender: "MALE",
      mood: "NOSTALGIC",
    });
    const persisted = localStorage.getItem("mobruji-recommend-defaults");
    expect(persisted).not.toBeNull();
    const parsed = JSON.parse(persisted as string);
    expect(parsed.state.ageGroup).toBe("FORTIES");
    expect(parsed.state.gender).toBe("MALE");
    expect(parsed.state.mood).toBe("NOSTALGIC");
    expect(parsed.state.onboardingCompleted).toBe(true);
  });
});
