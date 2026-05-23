/**
 * 추천 히스토리 페이지 테스트 (closes #134).
 *
 * 범위:
 *  - 빈 상태 → "아직 받은 추천이 없어요" + 음역대 입력 CTA 렌더.
 *  - 항목 다수 → 시간 라벨 + 미리보기 카드 + "+N개 더보기".
 *  - "+N개 더보기" 클릭 → 모든 곡 노출(복원 동작).
 *  - 삭제 버튼 → store.removeRecommendation 호출.
 *  - 전체 삭제 → confirm 후 clearHistory 호출.
 *  - a11y 위반 없음.
 */

import { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import HistoryPage from "./page";
import { expectNoA11yViolations } from "@/lib/test-helpers/a11y";
import type {
  RecommendationHistoryEntry,
  RecommendationHistoryInput,
} from "@/store/history";

// SongCard가 React Query mutation을 사용하므로 QueryClientProvider 래핑이 필요.
// HistoryPage가 미리보기 카드 안에 SongCard를 렌더.
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

const { historyMock, sessionMock } = await vi.hoisted(async () => {
  const historyHelper = await import("@/lib/test-helpers/mock-history-store");
  const sessionHelper = await import("@/lib/test-helpers/mock-session-store");
  return {
    historyMock: historyHelper.buildHistoryStoreMock(),
    sessionMock: sessionHelper.buildSessionStoreMock(),
  };
});

vi.mock("@/store/history", async () => {
  const actual =
    await vi.importActual<typeof import("@/store/history")>("@/store/history");
  return {
    ...actual,
    useHistoryStore: historyMock.useHistoryStore,
  };
});

vi.mock("@/store/session", () => ({
  useSessionStore: sessionMock.useSessionStore,
}));

// voice-range-history API mock — 기본 동작은 BE 호출 비활성(sessionId=null)이지만,
// sessionId 가 세팅된 케이스에선 이 mock 의 응답이 React Query 로 흘러간다.
const { readVoiceRangeHistoryMock } = vi.hoisted(() => ({
  readVoiceRangeHistoryMock: vi.fn(),
}));
vi.mock("@/lib/api/voiceRangeHistory", () => ({
  readVoiceRangeHistory: readVoiceRangeHistoryMock,
}));

// recommendation-history API mock (closes #263 — PR D).
// 기본은 빈 배열(BE 가 있어도 store fallback 으로 흐름이 결정되도록).
const { readRecommendationHistoryMock } = vi.hoisted(() => ({
  readRecommendationHistoryMock: vi.fn(),
}));
vi.mock("@/lib/api/recommendationHistory", () => ({
  readRecommendationHistory: readRecommendationHistoryMock,
}));

/**
 * issue #422 후속: BE `requestId` 가 UUIDv7 문자열로 전환됨에 따라 fixture 헬퍼.
 * 정수 시드로 결정적 UUID 문자열을 만든다 — 호출부의 의미(어떤 번호 entry 인지)를
 * 보존하면서 string 타입 정합성을 맞춘다.
 */
function ridFromSeed(seed: number): string {
  return `01933b1c-7f8a-7c2d-9b3e-${seed.toString(16).padStart(12, "0")}`;
}

function buildEntry(
  id: string,
  requestedAt: string,
  songIds: number[],
  overrides: Partial<RecommendationHistoryEntry> = {},
): RecommendationHistoryEntry {
  return {
    id,
    requestedAt,
    requestId: ridFromSeed(parseInt(id.replace(/\D/g, ""), 10) || 1),
    voiceRangeId: 42,
    excludedSongIds: [],
    songs: songIds.map((songId, idx) => ({
      rankPosition: idx + 1,
      score: 0.9 - idx * 0.05,
      matchReason: "음역 매칭",
      song: {
        id: songId,
        title: `곡-${songId}`,
        artist: `가수-${songId}`,
        releaseYear: 2024,
        keyOriginal: "C_MAJOR",
        bpm: 110,
        mood: "UPBEAT",
        language: "ko",
        genre: "POP",
        tjNumber: `T-${songId}`,
        kyNumber: `K-${songId}`,
        metadataSource: "MANUAL_SEED",
      },
    })),
    ...overrides,
  };
}

beforeEach(() => {
  historyMock.reset();
  sessionMock.reset();
  readVoiceRangeHistoryMock.mockReset();
  // 기본은 빈 시계열 (BE 호출이 일어나도 카드가 store fallback 으로 결정되도록).
  readVoiceRangeHistoryMock.mockResolvedValue({
    voiceRangeSnapshotResponses: [],
  });
  readRecommendationHistoryMock.mockReset();
  // 기본은 빈 BE 히스토리 — 기존 테스트들이 localStorage fallback 경로를 그대로 타도록.
  readRecommendationHistoryMock.mockResolvedValue({
    recommendationHistoryResponses: [],
  });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("HistoryPage", () => {
  it("빈 상태에서 안내 문구와 음역대 입력 CTA를 렌더한다", () => {
    renderWithQueryClient(<HistoryPage />);

    expect(
      screen.getByRole("heading", { name: /아직 받은 추천이 없어요/ }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: /음역대 입력하러 가기/ }),
    ).toBeInTheDocument();
  });

  it("항목이 있으면 상대 시간 + 추천 곡 미리보기(최대 3건)을 노출한다", () => {
    // 5곡짜리 항목 — 미리보기 3건만 노출되고 "+N개 더보기" 안내.
    const tenMinutesAgo = new Date(Date.now() - 10 * 60 * 1000).toISOString();
    historyMock.set({
      recommendations: [buildEntry("e-1", tenMinutesAgo, [10, 20, 30, 40, 50])],
    });

    renderWithQueryClient(<HistoryPage />);

    // 시간 라벨 — 10분 전.
    expect(screen.getByText(/10분 전/)).toBeInTheDocument();
    // 미리보기 3곡만 노출.
    expect(screen.getByText("곡-10")).toBeInTheDocument();
    expect(screen.getByText("곡-20")).toBeInTheDocument();
    expect(screen.getByText("곡-30")).toBeInTheDocument();
    expect(screen.queryByText("곡-40")).not.toBeInTheDocument();
    expect(screen.queryByText("곡-50")).not.toBeInTheDocument();
    // "+N개 더보기"
    expect(
      screen.getByRole("button", { name: /이 추천 다시 보기/ }),
    ).toBeInTheDocument();
  });

  it("'이 추천 다시 보기' 클릭 시 숨겨진 곡들이 모두 펼쳐진다 (복원 동작)", async () => {
    const tenMinutesAgo = new Date(Date.now() - 10 * 60 * 1000).toISOString();
    historyMock.set({
      recommendations: [buildEntry("e-1", tenMinutesAgo, [10, 20, 30, 40, 50])],
    });
    const user = userEvent.setup();

    renderWithQueryClient(<HistoryPage />);

    expect(screen.queryByText("곡-40")).not.toBeInTheDocument();

    await user.click(
      screen.getByRole("button", { name: /이 추천 다시 보기/ }),
    );

    // 펼친 뒤 모든 곡 노출.
    expect(screen.getByText("곡-10")).toBeInTheDocument();
    expect(screen.getByText("곡-40")).toBeInTheDocument();
    expect(screen.getByText("곡-50")).toBeInTheDocument();
    // 토글 라벨이 "접기"로 바뀌었는지.
    expect(screen.getByRole("button", { name: "접기" })).toBeInTheDocument();
  });

  it("삭제 버튼 클릭 시 store.removeRecommendation 이 해당 id 로 호출된다", async () => {
    const tenMinutesAgo = new Date(Date.now() - 10 * 60 * 1000).toISOString();
    historyMock.set({
      recommendations: [buildEntry("entry-abc", tenMinutesAgo, [1, 2, 3])],
    });
    const user = userEvent.setup();

    renderWithQueryClient(<HistoryPage />);

    const removeBtn = screen.getByRole("button", { name: /추천 삭제/ });
    await user.click(removeBtn);

    expect(historyMock.state().removeRecommendation).toHaveBeenCalledWith(
      "entry-abc",
    );
  });

  it("'전체 삭제' confirm 수락 시 clearHistory 가 호출된다", async () => {
    const tenMinutesAgo = new Date(Date.now() - 10 * 60 * 1000).toISOString();
    historyMock.set({
      recommendations: [buildEntry("e-1", tenMinutesAgo, [1])],
    });
    // happy-dom 환경에서는 window.confirm 이 존재하지 않을 수 있어 stubGlobal 로 강제 주입.
    const confirmMock = vi.fn(() => true);
    vi.stubGlobal("confirm", confirmMock);
    const user = userEvent.setup();

    renderWithQueryClient(<HistoryPage />);
    await user.click(screen.getByRole("button", { name: "전체 삭제" }));

    expect(confirmMock).toHaveBeenCalled();
    expect(historyMock.state().clearHistory).toHaveBeenCalledTimes(1);

    vi.unstubAllGlobals();
  });

  it("'전체 삭제' confirm 거절 시 clearHistory 는 호출되지 않는다", async () => {
    const tenMinutesAgo = new Date(Date.now() - 10 * 60 * 1000).toISOString();
    historyMock.set({
      recommendations: [buildEntry("e-1", tenMinutesAgo, [1])],
    });
    const confirmMock = vi.fn(() => false);
    vi.stubGlobal("confirm", confirmMock);
    const user = userEvent.setup();

    renderWithQueryClient(<HistoryPage />);
    await user.click(screen.getByRole("button", { name: "전체 삭제" }));

    expect(historyMock.state().clearHistory).not.toHaveBeenCalled();

    vi.unstubAllGlobals();
  });

  describe("voice-range-progress (spec PR D)", () => {
    it("sessionId 가 있고 BE 시계열이 2건 이상이면 BE 응답 기반 카드(헤드라인 + delta)를 노출한다", async () => {
      // given: 세션 + 추천 1건(empty progress) + BE 시계열 2건.
      const tenMinutesAgo = new Date(
        Date.now() - 10 * 60 * 1000,
      ).toISOString();
      historyMock.set({
        recommendations: [buildEntry("e-1", tenMinutesAgo, [1])],
      });
      sessionMock.set({ sessionId: "sess-be" });
      readVoiceRangeHistoryMock.mockResolvedValueOnce({
        voiceRangeSnapshotResponses: [
          {
            id: 11,
            lowMidi: 52,
            highMidi: 70,
            lowestNoteName: "E3",
            highestNoteName: "A4",
            sourceMethod: "SELF_REPORT",
            measuredAt: "2026-05-21T08:00:00",
          },
          {
            id: 22,
            lowMidi: 50,
            highMidi: 74,
            lowestNoteName: "D3",
            highestNoteName: "D5",
            sourceMethod: "MIC_MEASURE",
            measuredAt: "2026-05-21T12:00:00",
          },
        ],
      });

      // when:
      renderWithQueryClient(<HistoryPage />);

      // then: BE 호출이 발생하고 카드가 spanDelta=+6 헤드라인을 표시한다.
      expect(
        await screen.findByRole("heading", { name: /\+6 반음 넓어졌어요/ }),
      ).toBeInTheDocument();
      // 첫 측정 대비 lowMidi/highMidi delta 메타도 노출(spec §3).
      expect(
        screen.getByText(/저음 -2 반음.*고음 \+4 반음/),
      ).toBeInTheDocument();
      expect(readVoiceRangeHistoryMock).toHaveBeenCalledWith(
        "sess-be",
        expect.anything(),
      );
    });

    it("sessionId 없으면 BE 호출을 건너뛰고 localStorage(useHistoryStore) 만으로 카드를 결정한다", () => {
      // given: sessionId 없음 + store entries 2건(BE 없이도 카드 그려질 만큼).
      const t1 = new Date(Date.now() - 20 * 60 * 1000).toISOString();
      const t2 = new Date(Date.now() - 10 * 60 * 1000).toISOString();
      historyMock.set({
        recommendations: [
          buildEntry("e-newer", t2, [10], {
            voiceRangeLowMidi: 50,
            voiceRangeHighMidi: 74,
          }),
          buildEntry("e-older", t1, [20], {
            voiceRangeLowMidi: 52,
            voiceRangeHighMidi: 70,
          }),
        ],
      });
      // sessionMock.set 으로 sessionId 지정 안함 → enabled=false → BE 호출 0회.

      renderWithQueryClient(<HistoryPage />);

      expect(
        screen.getByRole("heading", { name: /\+6 반음 넓어졌어요/ }),
      ).toBeInTheDocument();
      expect(readVoiceRangeHistoryMock).not.toHaveBeenCalled();
    });

    it("BE 호출이 에러여도 localStorage entries 가 충분하면 graceful fallback 으로 카드를 그린다", async () => {
      const t1 = new Date(Date.now() - 20 * 60 * 1000).toISOString();
      const t2 = new Date(Date.now() - 10 * 60 * 1000).toISOString();
      historyMock.set({
        recommendations: [
          buildEntry("e-newer", t2, [10], {
            voiceRangeLowMidi: 50,
            voiceRangeHighMidi: 74,
          }),
          buildEntry("e-older", t1, [20], {
            voiceRangeLowMidi: 52,
            voiceRangeHighMidi: 70,
          }),
        ],
      });
      sessionMock.set({ sessionId: "sess-err" });
      readVoiceRangeHistoryMock.mockRejectedValue(new Error("network down"));

      renderWithQueryClient(<HistoryPage />);

      // BE 가 에러여도 store 기반으로 카드가 즉시 보여야 한다 (offline-first).
      expect(
        await screen.findByRole("heading", { name: /\+6 반음 넓어졌어요/ }),
      ).toBeInTheDocument();
    });
  });

  describe("recommendation-history backend source-of-truth (spec PR D)", () => {
    it("sessionId 가 있고 BE 응답이 1건 이상이면 BE entry 가 카드 리스트에 노출되고, localStorage entry 는 가린다", async () => {
      // given: localStorage 에는 곡-A 가 있지만 BE 에는 곡-100 만 있다.
      const tenMinutesAgo = new Date(
        Date.now() - 10 * 60 * 1000,
      ).toISOString();
      historyMock.set({
        recommendations: [buildEntry("local-1", tenMinutesAgo, [1])],
      });
      sessionMock.set({ sessionId: "sess-be" });
      readRecommendationHistoryMock.mockResolvedValueOnce({
        recommendationHistoryResponses: [
          {
            requestId: ridFromSeed(9001),
            sessionId: "sess-be",
            voiceRangeLow: 52,
            voiceRangeHigh: 70,
            mood: "UPBEAT",
            preferredBpm: 120,
            requestedAt: new Date(Date.now() - 5 * 60 * 1000).toISOString(),
            recommendations: [
              {
                song: {
                  id: 100,
                  title: "BE곡-100",
                  artist: "BE가수",
                  releaseYear: 2024,
                  keyOriginal: "C_MAJOR",
                  bpm: 120,
                  mood: "UPBEAT",
                  language: "ko",
                  genre: "POP",
                  tjNumber: "T100",
                  kyNumber: "K100",
                  metadataSource: "MANUAL_SEED",
                },
                score: 0.95,
                matchReason: "음역 일치",
                rankPosition: 1,
              },
            ],
          },
        ],
      });

      renderWithQueryClient(<HistoryPage />);

      // BE 응답 곡명이 화면에 보이고 localStorage 곡명은 가려져야 한다.
      expect(await screen.findByText("BE곡-100")).toBeInTheDocument();
      expect(screen.queryByText("곡-1")).not.toBeInTheDocument();
      // 헤더 문구도 BE source 표기로 바뀐다.
      expect(
        screen.getByText(/세션 ID 기준 1건의 추천을 서버에서 불러왔어요/),
      ).toBeInTheDocument();
      expect(readRecommendationHistoryMock).toHaveBeenCalledWith(
        "sess-be",
        expect.anything(),
      );
    });

    it("BE entry 카드는 삭제 버튼을 노출하지 않는다 (BE mutation API 미구현, PR F 대기)", async () => {
      sessionMock.set({ sessionId: "sess-be" });
      readRecommendationHistoryMock.mockResolvedValueOnce({
        recommendationHistoryResponses: [
          {
            requestId: ridFromSeed(1),
            sessionId: "sess-be",
            voiceRangeLow: 52,
            voiceRangeHigh: 70,
            mood: null,
            preferredBpm: null,
            requestedAt: new Date(Date.now() - 5 * 60 * 1000).toISOString(),
            recommendations: [
              {
                song: {
                  id: 200,
                  title: "BE곡-200",
                  artist: "BE가수",
                  releaseYear: 2024,
                  keyOriginal: "C_MAJOR",
                  bpm: null,
                  mood: null,
                  language: "ko",
                  genre: "POP",
                  tjNumber: "T200",
                  kyNumber: "K200",
                  metadataSource: "MANUAL_SEED",
                },
                score: 0.9,
                matchReason: "음역 일치",
                rankPosition: 1,
              },
            ],
          },
        ],
      });

      renderWithQueryClient(<HistoryPage />);

      await screen.findByText("BE곡-200");
      // 카드 안에 "삭제" 버튼 없음. (전체 삭제 버튼은 별도 — 항상 노출.)
      expect(
        screen.queryByRole("button", { name: /추천 삭제/ }),
      ).not.toBeInTheDocument();
    });

    it("BE 응답이 비었으면 localStorage entry 가 그대로 노출된다 (fallback)", async () => {
      const tenMinutesAgo = new Date(
        Date.now() - 10 * 60 * 1000,
      ).toISOString();
      historyMock.set({
        recommendations: [buildEntry("local-1", tenMinutesAgo, [42])],
      });
      sessionMock.set({ sessionId: "sess-empty" });
      readRecommendationHistoryMock.mockResolvedValueOnce({
        recommendationHistoryResponses: [],
      });

      renderWithQueryClient(<HistoryPage />);

      // localStorage 곡명이 그대로 나와야 한다.
      expect(await screen.findByText("곡-42")).toBeInTheDocument();
    });

    it("BE 호출이 에러여도 localStorage entry 가 있으면 fallback 으로 렌더한다", async () => {
      const tenMinutesAgo = new Date(
        Date.now() - 10 * 60 * 1000,
      ).toISOString();
      historyMock.set({
        recommendations: [buildEntry("local-1", tenMinutesAgo, [77])],
      });
      sessionMock.set({ sessionId: "sess-401" });
      readRecommendationHistoryMock.mockRejectedValue(
        new Error("session id mismatch"),
      );

      renderWithQueryClient(<HistoryPage />);

      // BE 가 실패해도 localStorage 곡명이 즉시 보여야 한다 (offline-first).
      expect(await screen.findByText("곡-77")).toBeInTheDocument();
    });

    it("BE entry 의 mood/preferredBpm 메타가 부제목에 노출된다", async () => {
      sessionMock.set({ sessionId: "sess-meta" });
      readRecommendationHistoryMock.mockResolvedValueOnce({
        recommendationHistoryResponses: [
          {
            requestId: ridFromSeed(1),
            sessionId: "sess-meta",
            voiceRangeLow: 52,
            voiceRangeHigh: 70,
            mood: "CALM",
            preferredBpm: 85,
            requestedAt: new Date(Date.now() - 5 * 60 * 1000).toISOString(),
            recommendations: [
              {
                song: {
                  id: 300,
                  title: "BE곡-300",
                  artist: "가수",
                  releaseYear: 2024,
                  keyOriginal: "C_MAJOR",
                  bpm: 85,
                  mood: "CALM",
                  language: "ko",
                  genre: "BALLAD",
                  tjNumber: null,
                  kyNumber: null,
                  metadataSource: "MANUAL_SEED",
                },
                score: 0.8,
                matchReason: "분위기 매칭",
                rankPosition: 1,
              },
            ],
          },
        ],
      });

      renderWithQueryClient(<HistoryPage />);

      await screen.findByText("BE곡-300");
      // "잔잔한 · 85 BPM" 형태 — 정확한 문구가 카드 부제목에 등장한다.
      expect(screen.getByText(/잔잔한.*85 BPM/)).toBeInTheDocument();
    });

    it("BE source 일 때 하단 삭제 버튼 라벨이 '이 기기 캐시 비우기' 로 분기되고 confirm 문구에 서버 데이터 잔존 안내가 포함된다 (#295 항목 3)", async () => {
      // BE entries 가 있으면 "전체 삭제" 라는 라벨이 사용자 기대치(서버 영구 삭제)와 어긋남.
      // 라벨을 "이 기기 캐시 비우기" 로 분기하고 confirm 문구에 "다음 방문 시 다시 보입니다" 명시.
      sessionMock.set({ sessionId: "sess-clear-label" });
      readRecommendationHistoryMock.mockResolvedValueOnce({
        recommendationHistoryResponses: [
          {
            requestId: ridFromSeed(7),
            sessionId: "sess-clear-label",
            voiceRangeLow: 52,
            voiceRangeHigh: 70,
            mood: null,
            preferredBpm: null,
            requestedAt: new Date(Date.now() - 5 * 60 * 1000).toISOString(),
            recommendations: [
              {
                song: {
                  id: 500,
                  title: "BE곡-500",
                  artist: "가수",
                  releaseYear: 2024,
                  keyOriginal: "C_MAJOR",
                  bpm: null,
                  mood: null,
                  language: "ko",
                  genre: "POP",
                  tjNumber: null,
                  kyNumber: null,
                  metadataSource: "MANUAL_SEED",
                },
                score: 0.9,
                matchReason: "음역 일치",
                rankPosition: 1,
              },
            ],
          },
        ],
      });
      // 명시적으로 (message: string) => boolean 시그니처를 부여 — 그래야
      // mock.calls 가 [string][] 로 추론되어 첫 호출의 message 를 type-safe 하게 꺼낼 수 있다.
      const confirmMock = vi.fn<(message?: string) => boolean>(() => true);
      vi.stubGlobal("confirm", confirmMock);
      const user = userEvent.setup();

      renderWithQueryClient(<HistoryPage />);

      // BE entries 로딩 완료 후 라벨이 분기되어야 한다.
      await screen.findByText("BE곡-500");
      const clearBtn = await screen.findByRole("button", {
        name: "이 기기 캐시 비우기",
      });
      // 분기 후 기존 라벨은 더 이상 노출되지 않는다.
      expect(
        screen.queryByRole("button", { name: "전체 삭제" }),
      ).not.toBeInTheDocument();

      await user.click(clearBtn);

      // confirm 에 "서버"/"다시 보입니다" 안내가 포함된 분기 문구가 전달되어야 한다.
      expect(confirmMock).toHaveBeenCalledTimes(1);
      const passedMessage = confirmMock.mock.calls[0]?.[0] ?? "";
      expect(passedMessage).toMatch(/서버/);
      expect(passedMessage).toMatch(/다음 방문/);
      // clearHistory 는 localStorage 만 비우는 기존 동작 그대로 (회귀 없음).
      expect(historyMock.state().clearHistory).toHaveBeenCalledTimes(1);

      vi.unstubAllGlobals();
    });
  });

  // closes #435 — 카운트 영역이 polite 라이브 영역으로 마킹되고 BE source 분기/
  // localStorage entry 모두에서 메시지가 라이브 영역에 노출되어야 한다.
  // PR #428 /recommend, PR #433 /likes /bookmarks 와 동일 패턴.
  describe("카운트 라이브 영역 (#435)", () => {
    it("localStorage entry 일 때 라이브 영역에 '최근 N건' 메시지가 노출된다", () => {
      const tenMinutesAgo = new Date(Date.now() - 10 * 60 * 1000).toISOString();
      historyMock.set({
        recommendations: [
          buildEntry("e-1", tenMinutesAgo, [1]),
          buildEntry("e-2", tenMinutesAgo, [2]),
        ],
      });

      renderWithQueryClient(<HistoryPage />);

      const liveRegion = screen.getByTestId("history-count-live");
      expect(liveRegion).toHaveAttribute("role", "status");
      expect(liveRegion).toHaveAttribute("aria-live", "polite");
      expect(liveRegion).toHaveAttribute("aria-atomic", "true");
      expect(liveRegion).toHaveTextContent(/최근 2건의 추천을 기록해두었어요/);
    });

    it("BE entry 도착 시 라이브 영역에 '세션 ID 기준 N건' 메시지로 분기된다", async () => {
      sessionMock.set({ sessionId: "sess-live" });
      readRecommendationHistoryMock.mockResolvedValueOnce({
        recommendationHistoryResponses: [
          {
            requestId: ridFromSeed(1),
            sessionId: "sess-live",
            voiceRangeLow: 52,
            voiceRangeHigh: 70,
            mood: null,
            preferredBpm: null,
            requestedAt: new Date(Date.now() - 5 * 60 * 1000).toISOString(),
            recommendations: [
              {
                song: {
                  id: 999,
                  title: "BE곡-999",
                  artist: "가수",
                  releaseYear: 2024,
                  keyOriginal: "C_MAJOR",
                  bpm: null,
                  mood: null,
                  language: "ko",
                  genre: "POP",
                  tjNumber: null,
                  kyNumber: null,
                  metadataSource: "MANUAL_SEED",
                },
                score: 0.9,
                matchReason: "음역 일치",
                rankPosition: 1,
              },
            ],
          },
        ],
      });

      renderWithQueryClient(<HistoryPage />);

      // BE entries 로딩 완료 후 라이브 영역 메시지가 BE source 문구로 바뀐다.
      await screen.findByText("BE곡-999");
      const liveRegion = screen.getByTestId("history-count-live");
      expect(liveRegion).toHaveAttribute("role", "status");
      expect(liveRegion).toHaveAttribute("aria-live", "polite");
      expect(liveRegion).toHaveAttribute("aria-atomic", "true");
      expect(liveRegion).toHaveTextContent(
        /세션 ID 기준 1건의 추천을 서버에서 불러왔어요/,
      );
    });
  });

  describe("a11y", () => {
    it("빈 상태에 a11y 위반이 없다", async () => {
      const { container } = renderWithQueryClient(<HistoryPage />);
      await expectNoA11yViolations(container);
    });

    it("히스토리 카드 리스트 상태에 a11y 위반이 없다", async () => {
      const tenMinutesAgo = new Date(
        Date.now() - 10 * 60 * 1000,
      ).toISOString();
      const input: RecommendationHistoryInput = {
        voiceRangeId: 1,
        requestId: ridFromSeed(1),
        songs: buildEntry("e-1", tenMinutesAgo, [1, 2, 3, 4]).songs,
        excludedSongIds: [99],
      };
      historyMock.set({
        recommendations: [
          { id: "e-1", requestedAt: tenMinutesAgo, ...input },
        ],
      });

      const { container } = renderWithQueryClient(<HistoryPage />);
      await expectNoA11yViolations(container);
    });
  });
});
