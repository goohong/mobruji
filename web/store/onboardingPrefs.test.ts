/**
 * 온보딩 취향 프로필 스토어 단위 테스트 (closes #1814).
 *
 * 범위:
 *  - 초기값은 세 필드 모두 null.
 *  - setPrefs 는 명시 값/null(스킵) 을 반영하고, undefined 키는 기존 값을 유지한다.
 *  - reset 으로 전체 초기화.
 *  - persist storage key.
 */

import { beforeEach, describe, expect, it } from "vitest";

import { useOnboardingPrefsStore } from "./onboardingPrefs";

beforeEach(() => {
  if (typeof localStorage !== "undefined") {
    localStorage.clear();
  }
  useOnboardingPrefsStore.setState({ mood: null, ageGroup: null, gender: null });
});

describe("useOnboardingPrefsStore", () => {
  it("초기값은 mood/ageGroup/gender 모두 null", () => {
    const state = useOnboardingPrefsStore.getState();
    expect(state.mood).toBeNull();
    expect(state.ageGroup).toBeNull();
    expect(state.gender).toBeNull();
  });

  it("setPrefs 가 명시한 값들을 영속한다", () => {
    useOnboardingPrefsStore
      .getState()
      .setPrefs({ mood: "UPBEAT", ageGroup: "TWENTIES", gender: "FEMALE" });

    const state = useOnboardingPrefsStore.getState();
    expect(state.mood).toBe("UPBEAT");
    expect(state.ageGroup).toBe("TWENTIES");
    expect(state.gender).toBe("FEMALE");
  });

  it("undefined 키는 기존 값을 유지하고, 명시 null 은 해제한다", () => {
    useOnboardingPrefsStore
      .getState()
      .setPrefs({ mood: "CALM", ageGroup: "THIRTIES", gender: "MALE" });

    // mood 만 갱신, ageGroup 미지정(유지), gender 명시 해제.
    useOnboardingPrefsStore
      .getState()
      .setPrefs({ mood: "GROOVY", gender: null });

    const state = useOnboardingPrefsStore.getState();
    expect(state.mood).toBe("GROOVY");
    expect(state.ageGroup).toBe("THIRTIES");
    expect(state.gender).toBeNull();
  });

  it("reset 은 세 값을 모두 null 로 되돌린다", () => {
    useOnboardingPrefsStore
      .getState()
      .setPrefs({ mood: "POWERFUL", ageGroup: "FORTIES", gender: "MALE" });

    useOnboardingPrefsStore.getState().reset();

    const state = useOnboardingPrefsStore.getState();
    expect(state.mood).toBeNull();
    expect(state.ageGroup).toBeNull();
    expect(state.gender).toBeNull();
  });

  it("localStorage 영속 key 는 mobruji-onboarding-prefs", () => {
    useOnboardingPrefsStore.getState().setPrefs({ gender: "FEMALE" });
    expect(localStorage.getItem("mobruji-onboarding-prefs")).not.toBeNull();
  });
});
