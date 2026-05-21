/**
 * 추천 결과 페이지 테스트.
 *
 * 보강 범위 (closes #60):
 *  - sessionId가 zustand에 없으면 NoSessionFallback 노출.
 *  - sessionId 존재 + voice-range 조회 성공 + recommendation 성공 시 카드 리스트 렌더.
 *  - API 모듈(`@/lib/api/voice-range`, `@/lib/api/recommendation`) mock + 실제 QueryClient 사용.
 *  - zustand store mock은 공통 헬퍼 사용.
 *
 * 보강 범위 (closes #78 #80):
 *  - useEffect 자동 트리거가 "1회만" 호출되는지 회귀 가드.
 *    (PR #57에서 객체 의존성으로 useEffect 무한 재실행 → fix 후에도 자동 테스트가 없었음.)
 *    아래 케이스로 mutate 호출 횟수를 정확히 검증한다:
 *      1) 같은 props로 재렌더해도 mutate 호출은 1회로 유지
 *      2) voice-range 응답(low/high)이 바뀌면 mutate가 정확히 1회 추가 호출
 *      3) sessionId가 바뀌면 mutate가 정확히 1회 추가 호출
 */

import { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  act,
  cleanup,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import RecommendPage from "./page";
import { readVoiceRange } from "@/lib/api/voice-range";
import { createRecommendation } from "@/lib/api/recommendation";
import { expectNoA11yViolations } from "@/lib/test-helpers/a11y";

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
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  }
  // wrapper 옵션을 통해 rerender 시에도 동일 Provider 트리가 유지되도록 한다.
  return render(ui, { wrapper: Wrapper });
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
    // 초기 excludeSongIds는 빈 배열 — store 누적이 비어 있는 상태.
    await waitFor(() => {
      expect(createRecommendationMock).toHaveBeenCalledWith({
        sessionId: "sess-abc",
        voiceRangeLow: 48,
        voiceRangeHigh: 69,
        excludeSongIds: [],
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

  // ---------- closes #78 #80 ----------
  //
  // PR #57(useEffect 의존성 분해)로 무한 재실행 함정은 해소됐지만,
  // "1회 mutate" 동작에 대한 자동 회귀 가드가 없었다. 본 블록에서
  // mutate spy 호출 횟수를 직접 측정해 회귀를 잡는다.

  /**
   * 추천 응답 1건 분량을 만드는 헬퍼. 테스트 본문에서 매번 같은 응답을 쓰면
   * "재렌더에도 mutate 1회"의 의도와 어긋날 수 있으므로 응답을 살짝 다르게 만든다.
   */
  function buildRecommendationResponse(
    requestId: number,
    title: string,
  ): Awaited<ReturnType<typeof createRecommendation>> {
    return {
      requestId,
      recommendations: [
        {
          rankPosition: 1,
          score: 0.9,
          matchReason: "음역 매칭",
          song: {
            id: requestId,
            title,
            artist: "테스트 가수",
            releaseYear: 2024,
            keyOriginal: "C_MAJOR",
            bpm: 110,
            mood: "UPBEAT",
            language: "ko",
            genre: "POP",
            tjNumber: `T-${requestId}`,
            kyNumber: `K-${requestId}`,
            metadataSource: "MANUAL_SEED",
          },
        },
      ],
    };
  }

  it("같은 props로 재렌더해도 recommendation mutate는 정확히 1회만 호출된다", async () => {
    sessionMock.set({ sessionId: "sess-stable", voiceRangeId: 7 });

    readVoiceRangeMock.mockResolvedValue({
      id: 7,
      sessionId: "sess-stable",
      lowestNoteMidi: 50,
      highestNoteMidi: 70,
      sourceMethod: "OCTAVE_PICK",
      createdAt: "2026-05-21T00:00:00Z",
      updatedAt: "2026-05-21T00:00:00Z",
    });

    createRecommendationMock.mockResolvedValue(
      buildRecommendationResponse(101, "안정 곡"),
    );

    const { rerender } = renderWithQueryClient(<RecommendPage />);

    // 1차: voice-range 조회 성공 → useEffect가 mutate를 1회 트리거.
    await waitFor(() => {
      expect(createRecommendationMock).toHaveBeenCalledTimes(1);
    });
    expect(createRecommendationMock).toHaveBeenCalledWith({
      sessionId: "sess-stable",
      voiceRangeLow: 50,
      voiceRangeHigh: 70,
      excludeSongIds: [],
    });

    // 결과가 렌더될 때까지 대기 → mutation isIdle=false, isSuccess=true 상태로 전이.
    await waitFor(() => {
      expect(screen.getByText("안정 곡")).toBeInTheDocument();
    });

    // 동일한 트리 props로 재렌더를 여러 번 시도해도 useEffect는 트리거를 추가하지 않는다.
    rerender(<RecommendPage />);
    rerender(<RecommendPage />);
    rerender(<RecommendPage />);

    // microtask 한 사이클을 흘려 useEffect 잔재가 있다면 발화하도록 둔다.
    await act(async () => {
      await Promise.resolve();
    });

    expect(createRecommendationMock).toHaveBeenCalledTimes(1);
  });

  it("voice-range 응답이 변경되면 새 sessionId 마운트에서 recommendation mutate가 정확히 1회 더 호출된다", async () => {
    // 시나리오: 사용자가 음역대를 다시 입력해 voice-range가 갱신된 뒤
    // recommend 페이지로 돌아온다. (RecommendPage는 sessionId가 바뀌면
    // RecommendContent를 새 키로 마운트해 mutation을 새로 시작한다.)

    sessionMock.set({ sessionId: "sess-A", voiceRangeId: 1 });

    readVoiceRangeMock.mockResolvedValueOnce({
      id: 1,
      sessionId: "sess-A",
      lowestNoteMidi: 48,
      highestNoteMidi: 60,
      sourceMethod: "OCTAVE_PICK",
      createdAt: "2026-05-21T00:00:00Z",
      updatedAt: "2026-05-21T00:00:00Z",
    });
    createRecommendationMock.mockResolvedValueOnce(
      buildRecommendationResponse(201, "A 음역 곡"),
    );

    const first = renderWithQueryClient(<RecommendPage />);

    await waitFor(() => {
      expect(createRecommendationMock).toHaveBeenCalledTimes(1);
    });
    expect(createRecommendationMock).toHaveBeenNthCalledWith(1, {
      sessionId: "sess-A",
      voiceRangeLow: 48,
      voiceRangeHigh: 60,
      excludeSongIds: [],
    });

    await waitFor(() => {
      expect(screen.getByText("A 음역 곡")).toBeInTheDocument();
    });

    // 첫 페이지 인스턴스를 정리하고 새 세션으로 다시 마운트 —
    // 실제 사용자 흐름(다른 세션으로 로그인/재진입)을 모사한다.
    first.unmount();

    sessionMock.set({ sessionId: "sess-B", voiceRangeId: 2 });
    readVoiceRangeMock.mockResolvedValueOnce({
      id: 2,
      sessionId: "sess-B",
      lowestNoteMidi: 55,
      highestNoteMidi: 75,
      sourceMethod: "OCTAVE_PICK",
      createdAt: "2026-05-21T00:01:00Z",
      updatedAt: "2026-05-21T00:01:00Z",
    });
    createRecommendationMock.mockResolvedValueOnce(
      buildRecommendationResponse(202, "B 음역 곡"),
    );

    const second = renderWithQueryClient(<RecommendPage />);

    await waitFor(() => {
      expect(createRecommendationMock).toHaveBeenCalledTimes(2);
    });
    expect(createRecommendationMock).toHaveBeenNthCalledWith(2, {
      sessionId: "sess-B",
      voiceRangeLow: 55,
      voiceRangeHigh: 75,
      excludeSongIds: [],
    });

    await waitFor(() => {
      expect(screen.getByText("B 음역 곡")).toBeInTheDocument();
    });

    // 두 번째 인스턴스에서 추가 재렌더에도 더 이상 호출은 늘지 않는다 (useEffect 1회 보장).
    second.rerender(<RecommendPage />);
    second.rerender(<RecommendPage />);
    await act(async () => {
      await Promise.resolve();
    });
    expect(createRecommendationMock).toHaveBeenCalledTimes(2);
  });

  // ---------- closes #83 #84 ----------
  //
  // "다른 곡 추천받기" 버튼이 store의 누적 excludedSongIds와 함께 호출하고,
  // 응답을 store에 다시 누적하는 흐름을 검증한다. 페이지 mock은 selector
  // 패턴이라 appendExcluded를 실제 누적으로 wire해 두 번째 호출 시점에
  // store가 첫 응답을 반영하고 있는지 본다.

  /**
   * 추천 응답 헬퍼 — 곡 ID 리스트로 응답을 생성.
   */
  function buildResponseWithSongIds(
    requestId: number,
    songIds: number[],
  ): Awaited<ReturnType<typeof createRecommendation>> {
    return {
      requestId,
      recommendations: songIds.map((id, idx) => ({
        rankPosition: idx + 1,
        score: 0.9 - idx * 0.05,
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
      })),
    };
  }

  /**
   * sessionMock의 appendExcluded를 실제 누적 로직으로 wire한다.
   * "다시 추천" 흐름에서 두 번째 호출이 첫 응답을 반영하는지 검증하기 위함.
   */
  function wireAppendExcluded() {
    const append = vi.fn((ids: number[]) => {
      const merged = new Set(sessionMock.state().excludedSongIds);
      for (const id of ids) merged.add(id);
      sessionMock.set({ excludedSongIds: Array.from(merged) });
    });
    sessionMock.set({ appendExcluded: append });
    return append;
  }

  it("'다른 곡 추천받기' 클릭 시 이전 곡 ID들이 excludeSongIds에 포함되어 호출된다", async () => {
    sessionMock.set({ sessionId: "sess-again", voiceRangeId: 11 });
    wireAppendExcluded();

    readVoiceRangeMock.mockResolvedValue({
      id: 11,
      sessionId: "sess-again",
      lowestNoteMidi: 50,
      highestNoteMidi: 70,
      sourceMethod: "OCTAVE_PICK",
      createdAt: "2026-05-21T00:00:00Z",
      updatedAt: "2026-05-21T00:00:00Z",
    });

    createRecommendationMock
      .mockResolvedValueOnce(buildResponseWithSongIds(1, [10, 20]))
      .mockResolvedValueOnce(buildResponseWithSongIds(2, [30, 40]));

    const user = userEvent.setup();
    renderWithQueryClient(<RecommendPage />);

    // 첫 호출은 빈 excludeSongIds.
    await waitFor(() => {
      expect(createRecommendationMock).toHaveBeenNthCalledWith(1, {
        sessionId: "sess-again",
        voiceRangeLow: 50,
        voiceRangeHigh: 70,
        excludeSongIds: [],
      });
    });

    // 첫 응답 카드 렌더 대기.
    await waitFor(() => {
      expect(screen.getByText("곡-10")).toBeInTheDocument();
    });

    // 응답 곡 ID가 store에 누적되었는지 확인 (appendExcluded wire 덕분).
    expect(sessionMock.state().excludedSongIds).toEqual([10, 20]);

    // "다른 곡 추천받기" 클릭.
    const againButton = screen.getByRole("button", {
      name: "다른 곡 추천받기",
    });
    await user.click(againButton);

    await waitFor(() => {
      expect(createRecommendationMock).toHaveBeenCalledTimes(2);
    });
    expect(createRecommendationMock).toHaveBeenNthCalledWith(2, {
      sessionId: "sess-again",
      voiceRangeLow: 50,
      voiceRangeHigh: 70,
      excludeSongIds: [10, 20],
    });
  });

  it("'다른 곡 추천받기'를 두 번 클릭하면 누적된 ID들이 모두 excludeSongIds에 포함된다", async () => {
    sessionMock.set({ sessionId: "sess-accum", voiceRangeId: 22 });
    wireAppendExcluded();

    readVoiceRangeMock.mockResolvedValue({
      id: 22,
      sessionId: "sess-accum",
      lowestNoteMidi: 48,
      highestNoteMidi: 72,
      sourceMethod: "OCTAVE_PICK",
      createdAt: "2026-05-21T00:00:00Z",
      updatedAt: "2026-05-21T00:00:00Z",
    });

    createRecommendationMock
      .mockResolvedValueOnce(buildResponseWithSongIds(1, [100, 200]))
      .mockResolvedValueOnce(buildResponseWithSongIds(2, [300, 400]))
      .mockResolvedValueOnce(buildResponseWithSongIds(3, [500, 600]));

    const user = userEvent.setup();
    renderWithQueryClient(<RecommendPage />);

    await waitFor(() => {
      expect(screen.getByText("곡-100")).toBeInTheDocument();
    });

    // 1차 클릭 — exclude는 첫 응답 ID만.
    await user.click(screen.getByRole("button", { name: "다른 곡 추천받기" }));
    await waitFor(() => {
      expect(screen.getByText("곡-300")).toBeInTheDocument();
    });
    expect(createRecommendationMock).toHaveBeenNthCalledWith(2, {
      sessionId: "sess-accum",
      voiceRangeLow: 48,
      voiceRangeHigh: 72,
      excludeSongIds: [100, 200],
    });

    // 2차 클릭 — 1차+2차 응답 ID가 누적되어 전달.
    await user.click(screen.getByRole("button", { name: "다른 곡 추천받기" }));
    await waitFor(() => {
      expect(createRecommendationMock).toHaveBeenCalledTimes(3);
    });
    expect(createRecommendationMock).toHaveBeenNthCalledWith(3, {
      sessionId: "sess-accum",
      voiceRangeLow: 48,
      voiceRangeHigh: 72,
      excludeSongIds: [100, 200, 300, 400],
    });
  });

  // closes #107 — 추천 결과 페이지는 NoSession fallback, 카드 리스트, 빈 결과 fallback
  // 세 상태가 모두 노출 가능하다. 각 상태에 대해 a11y 위반이 없어야 한다.
  describe("a11y", () => {
    it("NoSession fallback 상태에 a11y 위반이 없다", async () => {
      const { container } = renderWithQueryClient(<RecommendPage />);
      expect(
        screen.getByRole("heading", {
          name: /음역대가 아직 등록되지 않았습니다/,
        }),
      ).toBeInTheDocument();
      await expectNoA11yViolations(container);
    });

    it("카드 리스트 렌더 상태에 a11y 위반이 없다", async () => {
      sessionMock.set({ sessionId: "sess-a11y", voiceRangeId: 1 });
      readVoiceRangeMock.mockResolvedValue({
        id: 1,
        sessionId: "sess-a11y",
        lowestNoteMidi: 48,
        highestNoteMidi: 69,
        sourceMethod: "OCTAVE_PICK",
        createdAt: "2026-05-21T00:00:00Z",
        updatedAt: "2026-05-21T00:00:00Z",
      });
      createRecommendationMock.mockResolvedValue({
        requestId: 1,
        recommendations: [
          {
            rankPosition: 1,
            score: 0.9,
            matchReason: "음역 매칭",
            song: {
              id: 1,
              title: "a11y 곡",
              artist: "a11y 가수",
              releaseYear: 2024,
              keyOriginal: "C_MAJOR",
              bpm: 110,
              mood: "UPBEAT",
              language: "ko",
              genre: "POP",
              tjNumber: "T-1",
              kyNumber: "K-1",
              metadataSource: "MANUAL_SEED",
            },
          },
        ],
      });

      const { container } = renderWithQueryClient(<RecommendPage />);

      await waitFor(() => {
        expect(screen.getByText("a11y 곡")).toBeInTheDocument();
      });

      await expectNoA11yViolations(container);
    });

    it("빈 응답 fallback 상태에 a11y 위반이 없다", async () => {
      sessionMock.set({ sessionId: "sess-empty-a11y", voiceRangeId: 9 });
      readVoiceRangeMock.mockResolvedValue({
        id: 9,
        sessionId: "sess-empty-a11y",
        lowestNoteMidi: 50,
        highestNoteMidi: 70,
        sourceMethod: "OCTAVE_PICK",
        createdAt: "2026-05-21T00:00:00Z",
        updatedAt: "2026-05-21T00:00:00Z",
      });
      createRecommendationMock.mockResolvedValueOnce({
        requestId: 1,
        recommendations: [],
      });

      const { container } = renderWithQueryClient(<RecommendPage />);

      await waitFor(() => {
        expect(
          screen.getByText(/더 이상 추천할 곡이 없어요/),
        ).toBeInTheDocument();
      });

      await expectNoA11yViolations(container);
    });
  });

  it("응답 곡 수가 0이면 fallback UX를 노출하고 '다른 곡 추천받기' 버튼은 숨긴다", async () => {
    sessionMock.set({ sessionId: "sess-empty", voiceRangeId: 33 });
    wireAppendExcluded();

    readVoiceRangeMock.mockResolvedValue({
      id: 33,
      sessionId: "sess-empty",
      lowestNoteMidi: 52,
      highestNoteMidi: 67,
      sourceMethod: "OCTAVE_PICK",
      createdAt: "2026-05-21T00:00:00Z",
      updatedAt: "2026-05-21T00:00:00Z",
    });

    createRecommendationMock.mockResolvedValueOnce({
      requestId: 999,
      recommendations: [],
    });

    renderWithQueryClient(<RecommendPage />);

    await waitFor(() => {
      expect(
        screen.getByText(
          /더 이상 추천할 곡이 없어요\. 음역대를 다시 입력해 보세요\./,
        ),
      ).toBeInTheDocument();
    });

    // 버튼은 숨김 + 음역대 재입력 CTA가 노출 (헤더 링크 + fallback CTA 모두 포함).
    expect(
      screen.queryByRole("button", { name: "다른 곡 추천받기" }),
    ).not.toBeInTheDocument();
    const cta = screen.getAllByRole("link", { name: "음역대 다시 입력" });
    expect(cta.length).toBeGreaterThanOrEqual(1);
  });
});
