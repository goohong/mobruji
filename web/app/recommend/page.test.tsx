/**
 * 추천 결과 페이지 테스트.
 *
 * 보강 범위 (closes #60):
 *  - sessionId가 zustand에 없으면 NoSessionFallback 노출.
 *  - sessionId 존재 + voice-range 조회 성공 + recommendation 성공 시 카드 리스트 렌더.
 *  - API 모듈(`@/lib/api/voice-range`, `@/lib/api/recommendation`) mock + 실제 QueryClient 사용.
 *  - zustand store mock은 공통 헬퍼 사용.
 */

import { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor } from "@testing-library/react";

import RecommendPage from "./page";
import { readVoiceRange } from "@/lib/api/voice-range";
import { createRecommendation } from "@/lib/api/recommendation";

const { sessionMock } = await vi.hoisted(async () => {
  const helper = await import("@/lib/test-helpers/mock-session-store");
  return { sessionMock: helper.buildSessionStoreMock() };
});

vi.mock("@/store/session", () => ({
  useSessionStore: sessionMock.useSessionStore,
}));

vi.mock("@/lib/api/voice-range", async () => {
  const actual =
    await vi.importActual<typeof import("@/lib/api/voice-range")>(
      "@/lib/api/voice-range",
    );
  return {
    ...actual,
    readVoiceRange: vi.fn(),
  };
});

vi.mock("@/lib/api/recommendation", async () => {
  const actual =
    await vi.importActual<typeof import("@/lib/api/recommendation")>(
      "@/lib/api/recommendation",
    );
  return {
    ...actual,
    createRecommendation: vi.fn(),
  };
});

const readVoiceRangeMock = vi.mocked(readVoiceRange);
const createRecommendationMock = vi.mocked(createRecommendation);

function renderWithQueryClient(ui: ReactNode) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

beforeEach(() => {
  sessionMock.reset();
  readVoiceRangeMock.mockReset();
  createRecommendationMock.mockReset();
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("RecommendPage", () => {
  it("sessionId가 없으면 음역대 입력 안내(FALLBACK)를 렌더한다", () => {
    // 기본 상태가 sessionId: null
    renderWithQueryClient(<RecommendPage />);
    expect(
      screen.getByRole("heading", {
        name: /음역대가 아직 등록되지 않았습니다/,
      }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: /음역대 입력하러 가기/ }),
    ).toBeInTheDocument();
  });

  it("sessionId가 있으면 voice-range 조회 → recommendation 호출 → 카드 리스트를 렌더한다", async () => {
    sessionMock.set({ sessionId: "sess-abc", voiceRangeId: 42 });

    readVoiceRangeMock.mockResolvedValueOnce({
      id: 42,
      sessionId: "sess-abc",
      lowestNoteMidi: 48,
      highestNoteMidi: 69,
      sourceMethod: "OCTAVE_PICK",
      createdAt: "2026-05-21T00:00:00Z",
      updatedAt: "2026-05-21T00:00:00Z",
    });

    createRecommendationMock.mockResolvedValueOnce({
      requestId: 100,
      recommendations: [
        {
          rankPosition: 1,
          score: 0.95,
          matchReason: "음역 매칭",
          song: {
            id: 1,
            title: "테스트 곡 A",
            artist: "가수 A",
            releaseYear: 2024,
            keyOriginal: "C_MAJOR",
            bpm: 120,
            mood: "UPBEAT",
            language: "ko",
            genre: "POP",
            tjNumber: "12345",
            kyNumber: "54321",
            metadataSource: "MANUAL_SEED",
          },
        },
        {
          rankPosition: 2,
          score: 0.88,
          matchReason: "음역 매칭",
          song: {
            id: 2,
            title: "테스트 곡 B",
            artist: "가수 B",
            releaseYear: 2023,
            keyOriginal: "G_SHARP_MINOR",
            bpm: 100,
            mood: "CALM",
            language: "ko",
            genre: "BALLAD",
            tjNumber: "22222",
            kyNumber: "33333",
            metadataSource: "MANUAL_SEED",
          },
        },
      ],
    });

    renderWithQueryClient(<RecommendPage />);

    // voice-range 조회가 sessionId로 호출되었는지 확인.
    await waitFor(() => {
      expect(readVoiceRangeMock).toHaveBeenCalledWith("sess-abc");
    });

    // recommendation 자동 트리거가 voice-range 정보를 그대로 전달했는지 확인.
    await waitFor(() => {
      expect(createRecommendationMock).toHaveBeenCalledWith({
        sessionId: "sess-abc",
        voiceRangeLow: 48,
        voiceRangeHigh: 69,
      });
    });

    // 카드 렌더 — 곡 제목/가수/키 라벨 노출.
    await waitFor(() => {
      expect(screen.getByText("테스트 곡 A")).toBeInTheDocument();
    });
    expect(screen.getByText("테스트 곡 B")).toBeInTheDocument();
    expect(screen.getByText("가수 A")).toBeInTheDocument();
    expect(screen.getByText("가수 B")).toBeInTheDocument();
    // formatMusicalKey("C_MAJOR") → "C Major", "G_SHARP_MINOR" → "G# Minor"
    expect(screen.getByText("C Major")).toBeInTheDocument();
    expect(screen.getByText("G# Minor")).toBeInTheDocument();
  });
});
