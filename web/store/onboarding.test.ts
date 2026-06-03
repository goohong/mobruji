/**
 * 온보딩 상태 store 테스트 (first-user-onboarding-flow.md §7 fe 단위).
 *
 * 검증:
 *  - 페르소나 카드 선택 → entryPath 기록 + step=MEASURE 전이.
 *  - 둘러보기 선택 → entryPath null 유지 + step=MEASURE 전이.
 *  - complete() → step=DONE + completedAt 기록(첫 추천 도달 완료 기준).
 *  - reset() → 초기 상태 복귀.
 *  - localStorage 영속(중간 재진입 대비).
 */

import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { useOnboardingStore } from "./onboarding";

function resetStore() {
  useOnboardingStore.getState().reset();
  if (typeof localStorage !== "undefined") {
    localStorage.removeItem("mobruji-onboarding");
  }
}

beforeEach(() => resetStore());
afterEach(() => resetStore());

describe("useOnboardingStore", () => {
  it("초기 상태는 entryPath=null, step=INTENT, completedAt=null", () => {
    const state = useOnboardingStore.getState();
    expect(state.entryPath).toBeNull();
    expect(state.step).toBe("INTENT");
    expect(state.completedAt).toBeNull();
  });

  it("selectEntryPath 는 경로를 기록하고 step 을 MEASURE 로 전이한다", () => {
    useOnboardingStore.getState().selectEntryPath("PRACTICE");
    const state = useOnboardingStore.getState();
    expect(state.entryPath).toBe("PRACTICE");
    expect(state.step).toBe("MEASURE");
    expect(state.completedAt).toBeNull();
  });

  it("selectBrowse 는 entryPath 를 null 로 두고 step 을 MEASURE 로 전이한다", () => {
    useOnboardingStore.getState().selectEntryPath("MOOD");
    useOnboardingStore.getState().selectBrowse();
    const state = useOnboardingStore.getState();
    expect(state.entryPath).toBeNull();
    expect(state.step).toBe("MEASURE");
  });

  it("setStep 으로 단계를 직접 전이할 수 있다", () => {
    useOnboardingStore.getState().setStep("RECOMMEND");
    expect(useOnboardingStore.getState().step).toBe("RECOMMEND");
  });

  it("complete 는 step 을 DONE 으로 전이하고 completedAt 을 기록한다", () => {
    const before = Date.now();
    useOnboardingStore.getState().selectEntryPath("BEGINNER");
    useOnboardingStore.getState().complete();
    const state = useOnboardingStore.getState();
    expect(state.step).toBe("DONE");
    expect(state.completedAt).not.toBeNull();
    expect(state.completedAt as number).toBeGreaterThanOrEqual(before);
    // 완료해도 선택한 경로는 유지(완료 후 핸드오프 분기에 사용).
    expect(state.entryPath).toBe("BEGINNER");
  });

  it("reset 은 초기 상태로 되돌린다", () => {
    useOnboardingStore.getState().selectEntryPath("MOOD");
    useOnboardingStore.getState().complete();
    useOnboardingStore.getState().reset();
    const state = useOnboardingStore.getState();
    expect(state.entryPath).toBeNull();
    expect(state.step).toBe("INTENT");
    expect(state.completedAt).toBeNull();
  });

  it("선택 상태가 localStorage 에 영속되어 재진입 시 이어진다", () => {
    useOnboardingStore.getState().selectEntryPath("PRACTICE");
    const persisted = localStorage.getItem("mobruji-onboarding");
    expect(persisted).not.toBeNull();
    const parsed = JSON.parse(persisted as string);
    expect(parsed.state.entryPath).toBe("PRACTICE");
    expect(parsed.state.step).toBe("MEASURE");
  });
});
