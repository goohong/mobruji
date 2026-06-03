/**
 * P-D 모임 사회자(시퀀스) 페이지 테스트 (이슈 #1601).
 *
 * - sessionId 없음 → 음역대 입력 안내 fallback.
 * - 세션 + 음역대 + 시퀀스 엔드포인트 성공 → 도입 단계 곡 렌더 + persona=P-D 요청.
 * - 시퀀스 엔드포인트 404(be #1599 미머지) → 기존 추천 기반 client fallback 으로 단계 렌더.
 * - 연령대 칩 다중 선택 → ageGroups 포함해 시퀀스 재요청.
 * - a11y 위반 없음.
 */

import { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import HostRecommendPage from "./page";
import { readVoiceRange } from "@/lib/api/voice-range";
import {
  createRecommendation,
  createSequenceRecommendation,
} from "@/lib/api/recommendation";
import { ApiError } from "@/lib/api/client";
import { expectNoA11yViolations } from "@/lib/test-helpers/a11y";

const { sessionMock } = await vi.hoisted(async () => {
  const sessionHelper = await import("@/lib/test-helpers/mock-session-store");
  return { sessionMock: sessionHelper.buildSessionStoreMock() };
});

vi.mock("@/store/session", () => ({
  useSessionStore: sessionMock.useSessionStore,
}));

vi.mock("@/lib/api/voice-range", async () => {
  const actual =
    await vi.importActual<typeof import("@/lib/api/voice-range")>(
      "@/lib/api/voice-range",
    );
  return { ...actual, readVoiceRange: vi.fn() };
});

vi.mock("@/lib/api/feedback", () => ({
  toggleLike: vi.fn(),
  toggleBookmark: vi.fn(),
}));

vi.mock("@/lib/api/recommendation", async () => {
  const actual =
    await vi.importActual<typeof import("@/lib/api/recommendation")>(
      "@/lib/api/recommendation",
    );
  return {
    ...actual,
    createRecommendation: vi.fn(),
    createSequenceRecommendation: vi.fn(),
  };
});

const readVoiceRangeMock = vi.mocked(readVoiceRange);
const createRecommendationMock = vi.mocked(createRecommendation);
const createSequenceRecommendationMock = vi.mocked(createSequenceRecommendation);

function renderWithQueryClient(ui: ReactNode) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    );
  }
  return render(ui, { wrapper: Wrapper });
}

function voiceRange(sessionId: string) {
  return {
    id: 1,
    sessionId,
    lowestNoteMidi: 48,
    highestNoteMidi: 69,
    sourceMethod: "OCTAVE_PICK" as const,
    createdAt: "2026-06-03T00:00:00Z",
    updatedAt: "2026-06-03T00:00:00Z",
  };
}

function song(id: number) {
  return {
    rankPosition: id,
    score: 0.9,
    matchReason: "음역 매칭",
    song: {
      id,
      title: `곡-${id}`,
      artist: "가수",
      releaseYear: 2024,
      keyOriginal: "C_MAJOR" as const,
      bpm: 110,
      mood: "UPBEAT" as const,
      language: "ko",
      genre: "POP",
      tjNumber: `T-${id}`,
      kyNumber: `K-${id}`,
      metadataSource: "MANUAL_SEED" as const,
    },
  };
}

const SAMPLE_RID = "01933b1c-7f8a-7c2d-9b3e-0123456789ab";

beforeEach(() => {
  sessionMock.reset();
  readVoiceRangeMock.mockReset();
  createRecommendationMock.mockReset();
  createSequenceRecommendationMock.mockReset();
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("HostRecommendPage", () => {
  it("sessionId 가 없으면 음역대 입력 안내를 렌더한다", () => {
    renderWithQueryClient(<HostRecommendPage />);
    expect(
      screen.getByRole("heading", {
        name: /음역대가 아직 등록되지 않았습니다/,
      }),
    ).toBeInTheDocument();
  });

  it("시퀀스 엔드포인트 성공 시 persona=P-D 로 요청하고 도입 단계 곡을 렌더한다", async () => {
    sessionMock.set({ sessionId: "sess-host", voiceRangeId: 1 });
    readVoiceRangeMock.mockResolvedValue(voiceRange("sess-host"));
    createSequenceRecommendationMock.mockResolvedValue({
      requestId: SAMPLE_RID,
      persona: "P-D",
      stages: [
        { stage: "INTRO", songs: [song(1)] },
        { stage: "PEAK", songs: [song(2)] },
        { stage: "FINALE", songs: [song(3)] },
      ],
    });

    renderWithQueryClient(<HostRecommendPage />);

    await waitFor(() => {
      expect(createSequenceRecommendationMock).toHaveBeenCalledWith({
        sessionId: "sess-host",
        voiceRangeLow: 48,
        voiceRangeHigh: 69,
        persona: "P-D",
      });
    });
    await waitFor(() => {
      expect(screen.getByText("곡-1")).toBeInTheDocument();
    });
    // 시퀀스 엔드포인트 성공 시 일반 추천 fallback 은 호출되지 않는다.
    expect(createRecommendationMock).not.toHaveBeenCalled();
  });

  it("시퀀스 엔드포인트 404 시 기존 추천 기반 client fallback 으로 단계를 렌더한다", async () => {
    sessionMock.set({ sessionId: "sess-fb", voiceRangeId: 1 });
    readVoiceRangeMock.mockResolvedValue(voiceRange("sess-fb"));
    createSequenceRecommendationMock.mockRejectedValue(
      new ApiError(404, "no route", null),
    );
    createRecommendationMock.mockResolvedValue({
      requestId: SAMPLE_RID,
      recommendations: [song(11), song(12), song(13)],
    });

    renderWithQueryClient(<HostRecommendPage />);

    await waitFor(() => {
      expect(createRecommendationMock).toHaveBeenCalledWith({
        sessionId: "sess-fb",
        voiceRangeLow: 48,
        voiceRangeHigh: 69,
      });
    });
    // fallback 으로 3등분 → 도입 단계 첫 곡이 보인다.
    await waitFor(() => {
      expect(screen.getByText("곡-11")).toBeInTheDocument();
    });
  });

  it("연령대 칩 선택 시 ageGroups 를 포함해 시퀀스를 재요청한다", async () => {
    const user = userEvent.setup();
    sessionMock.set({ sessionId: "sess-age", voiceRangeId: 1 });
    readVoiceRangeMock.mockResolvedValue(voiceRange("sess-age"));
    createSequenceRecommendationMock.mockResolvedValue({
      requestId: SAMPLE_RID,
      persona: "P-D",
      stages: [
        { stage: "INTRO", songs: [song(1)] },
        { stage: "PEAK", songs: [] },
        { stage: "FINALE", songs: [] },
      ],
    });

    renderWithQueryClient(<HostRecommendPage />);

    await waitFor(() => {
      expect(createSequenceRecommendationMock).toHaveBeenNthCalledWith(1, {
        sessionId: "sess-age",
        voiceRangeLow: 48,
        voiceRangeHigh: 69,
        persona: "P-D",
      });
    });

    await user.click(screen.getByRole("button", { name: "30대" }));

    await waitFor(() => {
      expect(createSequenceRecommendationMock).toHaveBeenCalledWith({
        sessionId: "sess-age",
        voiceRangeLow: 48,
        voiceRangeHigh: 69,
        persona: "P-D",
        ageGroups: ["THIRTIES"],
      });
    });
  });

  it("a11y 위반이 없다 (시퀀스 렌더 상태)", async () => {
    sessionMock.set({ sessionId: "sess-a11y", voiceRangeId: 1 });
    readVoiceRangeMock.mockResolvedValue(voiceRange("sess-a11y"));
    createSequenceRecommendationMock.mockResolvedValue({
      requestId: SAMPLE_RID,
      persona: "P-D",
      stages: [
        { stage: "INTRO", songs: [song(1)] },
        { stage: "PEAK", songs: [song(2)] },
        { stage: "FINALE", songs: [song(3)] },
      ],
    });

    const { container } = renderWithQueryClient(<HostRecommendPage />);
    await waitFor(() => {
      expect(screen.getByText("곡-1")).toBeInTheDocument();
    });
    await expectNoA11yViolations(container);
  });
});
