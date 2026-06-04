/**
 * 가입 후 온보딩 페이지 테스트 (closes #1814).
 *
 * 범위:
 *  - 성별·나이대·분위기 칩을 렌더하고 토글한다.
 *  - "시작하기": 고른 값을 prefs store 에 영속 + 성별은 updateProfile 로 BE 반영 →
 *    /recommend 라우팅.
 *  - 아무것도 안 골랐으면 updateProfile 호출 없이 prefs 는 null 영속 + /recommend 라우팅.
 *  - "건너뛰기": 저장 없이 /recommend 라우팅.
 */

import { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import OnboardingPage from "./page";
import { updateProfile } from "@/lib/api/auth";
import { useOnboardingPrefsStore } from "@/store/onboardingPrefs";

const { pushMock } = vi.hoisted(() => ({ pushMock: vi.fn() }));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock }),
}));

vi.mock("@/lib/api/auth", async () => {
  const actual =
    await vi.importActual<typeof import("@/lib/api/auth")>("@/lib/api/auth");
  return { ...actual, updateProfile: vi.fn() };
});

const updateProfileMock = vi.mocked(updateProfile);

function renderWithQueryClient(ui: ReactNode) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  return render(
    <QueryClientProvider client={client}>{ui}</QueryClientProvider>,
  );
}

beforeEach(() => {
  updateProfileMock.mockReset();
  updateProfileMock.mockResolvedValue({
    userId: 1,
    email: "a@b.com",
    authProvider: "LOCAL",
    gender: "FEMALE",
    vocalRangeLowMidi: null,
    vocalRangeHighMidi: null,
  });
  pushMock.mockReset();
  if (typeof localStorage !== "undefined") {
    localStorage.clear();
  }
  useOnboardingPrefsStore.setState({ mood: null, ageGroup: null, gender: null });
});

afterEach(() => {
  cleanup();
});

describe("OnboardingPage", () => {
  it("성별·나이대·분위기 칩을 렌더한다", () => {
    renderWithQueryClient(<OnboardingPage />);
    for (const label of ["남자곡", "여자곡", "20대", "신나는", "감성적인"]) {
      expect(screen.getByRole("button", { name: label })).toBeInTheDocument();
    }
  });

  it("고른 취향을 prefs 에 영속하고 성별은 updateProfile 로 반영한 뒤 /recommend 로 간다", async () => {
    const user = userEvent.setup();
    renderWithQueryClient(<OnboardingPage />);

    await user.click(screen.getByRole("button", { name: "여자곡" }));
    await user.click(screen.getByRole("button", { name: "20대" }));
    await user.click(screen.getByRole("button", { name: "신나는" }));
    await user.click(
      screen.getByRole("button", { name: "이 취향으로 시작하기" }),
    );

    await waitFor(() => {
      expect(updateProfileMock).toHaveBeenCalledWith({ gender: "FEMALE" });
    });
    await waitFor(() => {
      expect(pushMock).toHaveBeenCalledWith("/recommend");
    });

    const prefs = useOnboardingPrefsStore.getState();
    expect(prefs.gender).toBe("FEMALE");
    expect(prefs.ageGroup).toBe("TWENTIES");
    expect(prefs.mood).toBe("UPBEAT");
  });

  it("아무것도 안 고르면 updateProfile 없이 /recommend 로 간다", async () => {
    const user = userEvent.setup();
    renderWithQueryClient(<OnboardingPage />);

    await user.click(
      screen.getByRole("button", { name: "이 취향으로 시작하기" }),
    );

    await waitFor(() => {
      expect(pushMock).toHaveBeenCalledWith("/recommend");
    });
    expect(updateProfileMock).not.toHaveBeenCalled();
    expect(useOnboardingPrefsStore.getState().gender).toBeNull();
  });

  it("건너뛰기는 저장 없이 /recommend 로 간다", async () => {
    const user = userEvent.setup();
    renderWithQueryClient(<OnboardingPage />);

    await user.click(screen.getByRole("button", { name: "여자곡" }));
    await user.click(screen.getByRole("button", { name: "건너뛰기" }));

    expect(pushMock).toHaveBeenCalledWith("/recommend");
    expect(updateProfileMock).not.toHaveBeenCalled();
    expect(useOnboardingPrefsStore.getState().gender).toBeNull();
  });
});
