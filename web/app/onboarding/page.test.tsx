/**
 * 회원가입 온보딩 페이지 테스트 (closes #1814).
 *
 * 범위:
 *  - 완료: 고른 나이대·성별·취향을 recommendDefaults store 에 영속 + 성별은
 *    updateProfile 로 BE 반영 → router.push("/").
 *  - 건너뛰기: 아무 것도 저장하지 않고(모두 null, 완료 플래그만) 라우팅 — updateProfile 미호출.
 *  - 비로그인 직접 진입: 홈으로 replace(온보딩은 가입자 한정).
 *  - 일부만 골라도 그대로 저장(스킵 허용).
 */

import { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import OnboardingPage from "./page";
import { updateProfile } from "@/lib/api/auth";
import { useAuthStore } from "@/store/auth";
import { useRecommendDefaultsStore } from "@/store/recommendDefaults";

const { pushMock, replaceMock } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  replaceMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: replaceMock }),
}));

vi.mock("@/lib/api/auth", async () => {
  const actual =
    await vi.importActual<typeof import("@/lib/api/auth")>("@/lib/api/auth");
  return { ...actual, updateProfile: vi.fn() };
});

const updateProfileMock = vi.mocked(updateProfile);

function renderPage(ui: ReactNode) {
  return render(ui);
}

function loginState() {
  useAuthStore.setState({
    token: "tok-1",
    tokenExpiresAt: "2999-01-01T00:00:00",
    userId: 7,
    email: "a@b.com",
  });
}

beforeEach(() => {
  pushMock.mockReset();
  replaceMock.mockReset();
  updateProfileMock.mockReset();
  updateProfileMock.mockResolvedValue({
    userId: 7,
    email: "a@b.com",
    authProvider: "LOCAL",
    gender: "FEMALE",
    vocalRangeLowMidi: null,
    vocalRangeHighMidi: null,
  });
  if (typeof localStorage !== "undefined") {
    localStorage.clear();
  }
  useRecommendDefaultsStore.getState().reset();
  loginState();
});

afterEach(() => {
  cleanup();
});

describe("OnboardingPage", () => {
  it("고른 값을 store 에 영속하고 성별은 BE 반영 후 홈으로 라우팅한다", async () => {
    const user = userEvent.setup();
    renderPage(<OnboardingPage />);

    await user.click(screen.getByRole("button", { name: "20대" }));
    await user.click(screen.getByRole("button", { name: "여성" }));
    await user.click(screen.getByRole("button", { name: "신나는" }));
    await user.click(screen.getByRole("button", { name: "완료" }));

    await waitFor(() => {
      expect(pushMock).toHaveBeenCalledWith("/");
    });
    const state = useRecommendDefaultsStore.getState();
    expect(state.ageGroup).toBe("TWENTIES");
    expect(state.gender).toBe("FEMALE");
    expect(state.mood).toBe("UPBEAT");
    expect(state.onboardingCompleted).toBe(true);
    expect(updateProfileMock).toHaveBeenCalledWith({ gender: "FEMALE" });
  });

  it("건너뛰기는 모두 null 로 저장하고 updateProfile 을 호출하지 않는다", async () => {
    const user = userEvent.setup();
    renderPage(<OnboardingPage />);

    await user.click(screen.getByRole("button", { name: "건너뛰기" }));

    await waitFor(() => {
      expect(pushMock).toHaveBeenCalledWith("/");
    });
    const state = useRecommendDefaultsStore.getState();
    expect(state.ageGroup).toBeNull();
    expect(state.gender).toBeNull();
    expect(state.mood).toBeNull();
    expect(state.onboardingCompleted).toBe(true);
    expect(updateProfileMock).not.toHaveBeenCalled();
  });

  it("성별을 안 고르면 updateProfile 을 호출하지 않고 고른 값만 저장한다", async () => {
    const user = userEvent.setup();
    renderPage(<OnboardingPage />);

    await user.click(screen.getByRole("button", { name: "30대" }));
    await user.click(screen.getByRole("button", { name: "완료" }));

    await waitFor(() => {
      expect(pushMock).toHaveBeenCalledWith("/");
    });
    const state = useRecommendDefaultsStore.getState();
    expect(state.ageGroup).toBe("THIRTIES");
    expect(state.gender).toBeNull();
    expect(updateProfileMock).not.toHaveBeenCalled();
  });

  it("선택한 칩을 다시 누르면 해제된다(스킵 동일)", async () => {
    const user = userEvent.setup();
    renderPage(<OnboardingPage />);

    const twenties = screen.getByRole("button", { name: "20대" });
    await user.click(twenties);
    expect(twenties).toHaveAttribute("aria-pressed", "true");
    await user.click(twenties);
    expect(twenties).toHaveAttribute("aria-pressed", "false");

    await user.click(screen.getByRole("button", { name: "완료" }));
    await waitFor(() => {
      expect(pushMock).toHaveBeenCalledWith("/");
    });
    expect(useRecommendDefaultsStore.getState().ageGroup).toBeNull();
  });

  it("비로그인 상태로 직접 진입하면 홈으로 돌려보낸다", async () => {
    useAuthStore.setState({
      token: null,
      tokenExpiresAt: null,
      userId: null,
      email: null,
    });
    renderPage(<OnboardingPage />);

    await waitFor(() => {
      expect(replaceMock).toHaveBeenCalledWith("/");
    });
  });
});
