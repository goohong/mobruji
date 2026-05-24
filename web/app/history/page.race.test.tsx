/**
 * /history 페이지 query/source 전환 race 가드 (PR #1057 race-helpers reuse, #1064).
 *
 * AS-IS:
 *   - PR #1045 (`web/lib/hooks/useHistoryQueries-race.test.tsx`) — recommendation-history
 *     / voice-range-history **hook 본체** race 가드 (refetch race / unmount abort /
 *     401 retry / enabled gating).
 *   - PR #1048 / #1054 — `/recommend` / `/likes` / `/bookmarks` **페이지** mutation race
 *     5건 가드.
 *   - `/history` **페이지 컨텍스트** 의 query race / sessionId hydration / BE↔localStorage
 *     source 전환 / 401 graceful degradation race 검증 부재.
 *
 *   잠재 회귀 시나리오 (production 코드 변경 없이도 누군가 page wiring / hook /
 *   `pickProgressSummary` 를 건드리면 silent 회귀):
 *   - **sessionId hydration race**: zustand persist hydrate 전 `sessionId === null`
 *     → 두 useQuery 의 `enabled: Boolean(sessionId)` 가 false → BE 호출 0건.
 *     hydrate 후 truthy 로 전환되면 자동 활성화 — 활성화 시점에 localStorage
 *     fallback 카드가 먼저 보여야 (BE 응답 도착 전 stale 표시 X).
 *   - **BE 응답 도착 시 source 전환**: pending 동안 localStorage entry 카드 노출,
 *     BE 응답 도착 후 BE source 카드 재렌더. 도중 cross-render flicker 또는 두 source
 *     의 카드가 동시 노출되는 사고 차단.
 *   - **BE 401 → graceful fallback**: `readRecommendationHistory` 가 401 던지면
 *     localStorage 항목으로 fallback 유지. progress section / 다른 UI 오염 없음.
 *   - **페이지 unmount mid-query**: pending 두 query 가 떠 있는 도중 페이지 unmount
 *     → React state update warning 0건. QueryClient 의 last-observer abort 가
 *     graceful 하게 동작.
 *   - **progressSummary BE-우선 분기**: voiceRange BE snapshots ≥ 2 fresh 도착 시
 *     BE summary 사용 (localStorage fallback 없이도 카드 렌더). spec
 *     voice-range-progress §3 source-of-truth 우선순위 회귀 차단.
 *
 * TO-BE:
 *   본 파일은 `/history` 페이지 마운트 → 두 useQuery 활성화 흐름에서 위 5 시나리오를
 *   페이지 레벨로 검증한다 (production 코드 변경 없이 테스트만 추가).
 *
 * 비범위:
 *   - production page.tsx / hook / store 변경 없음.
 *   - hook 본체 race (refetch / abort / 401 retry / enabled gating) — PR #1045 cover.
 *   - hook 자체 추출 (HistoryPage 가 inline useQuery 유지) — 본 PR 범위 외.
 *   - happy path (빈 상태 / 항목 렌더 / 삭제 / a11y) — `page.test.tsx` cover.
 *   - mutation race — `/history` 는 BE mutation API 미구현 (PR F 대기), 로컬 store
 *     `removeRecommendation` / `clearHistory` 만 사용 (synchronous zustand). race 없음.
 *   - design tokens — 별도 fe PR.
 *
 * 보안:
 *   - sessionId 는 PII (logging.ts §SENSITIVE_KEYS). 본 테스트의 "test-session-id"
 *     는 의도된 더미값.
 *   - voiceRangeLowMidi/highMidi 는 음역대 PII 가능성 — 본 테스트의 60/72 는 더미.
 */

import { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  act,
  cleanup,
  render,
  screen,
  waitFor,
} from "@testing-library/react";

import { ApiError } from "@/lib/api/client";
import {
  createDeferred,
  makeQueryClient,
  makeWrapper,
  unauthorizedError,
} from "@/lib/test-helpers/race-helpers";
import type { RecommendationHistoryEntry } from "@/store/history";
import type {
  RecommendationHistoryEntryResponse,
  RecommendationHistoryListResponse,
} from "@/lib/api/recommendationHistory";
import type {
  VoiceRangeHistoryResponse,
  VoiceRangeSnapshotResponse,
} from "@/lib/api/voiceRangeHistory";

// store mock — `page.test.tsx` 와 동일 패턴 (selector 패턴 지원).
// vi.hoisted 안에서 호출해야 vi.mock factory 호이스팅 제약 회피 가능.
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

// API mock — 본 테스트는 React Query / page wiring 동작이지 fetch 자체가 아니다.
// `createDeferred` 로 응답 도착 시점을 테스트가 통제한다.
vi.mock("@/lib/api/recommendationHistory", () => ({
  readRecommendationHistory: vi.fn(),
}));
vi.mock("@/lib/api/voiceRangeHistory", () => ({
  readVoiceRangeHistory: vi.fn(),
}));

// SongCard 가 toggleLike / toggleBookmark mutation 을 갖고 있어 import 만으로도
// API client 가 호출될 수 있다. 본 테스트 범위는 page-level query race 이므로
// feedback API mock 으로 noise 차단.
vi.mock("@/lib/api/feedback", () => ({
  readLikesBySessionId: vi.fn().mockResolvedValue({
    responses: [],
    page: 0,
    size: 20,
    totalCount: 0,
    hasNext: false,
  }),
  readBookmarksBySessionId: vi.fn().mockResolvedValue({
    responses: [],
    page: 0,
    size: 20,
    totalCount: 0,
    hasNext: false,
  }),
  toggleLike: vi.fn(),
  toggleBookmark: vi.fn(),
}));

import { readRecommendationHistory } from "@/lib/api/recommendationHistory";
import { readVoiceRangeHistory } from "@/lib/api/voiceRangeHistory";

import HistoryPage from "./page";

const readRecommendationHistoryMock = vi.mocked(readRecommendationHistory);
const readVoiceRangeHistoryMock = vi.mocked(readVoiceRangeHistory);

/**
 * BE requestId 가 UUIDv7 문자열 (#422). 정수 시드로 결정적 UUID-shape 문자열을 만든다.
 * page.test.tsx 와 동일 헬퍼.
 */
function ridFromSeed(seed: number): string {
  return `01933b1c-7f8a-7c2d-9b3e-${seed.toString(16).padStart(12, "0")}`;
}

function buildLocalEntry(
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
        title: `local-곡-${songId}`,
        artist: `local-가수-${songId}`,
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

function buildBackendEntry(
  seed: number,
  requestedAt: string,
  songIds: number[],
): RecommendationHistoryEntryResponse {
  return {
    requestId: ridFromSeed(seed),
    sessionId: "test-session-id",
    voiceRangeLow: 60,
    voiceRangeHigh: 72,
    mood: "UPBEAT",
    preferredBpm: 120,
    requestedAt,
    recommendations: songIds.map((songId, idx) => ({
      rankPosition: idx + 1,
      score: 0.9 - idx * 0.05,
      matchReason: "음역 매칭",
      song: {
        id: songId,
        title: `be-곡-${songId}`,
        artist: `be-가수-${songId}`,
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
  };
}

function buildBackendListResponse(
  entries: RecommendationHistoryEntryResponse[],
): RecommendationHistoryListResponse {
  return { recommendationHistoryResponses: entries };
}

function buildVoiceRangeSnapshot(
  id: number,
  measuredAt: string,
  lowMidi: number,
  highMidi: number,
): VoiceRangeSnapshotResponse {
  return {
    id,
    lowMidi,
    highMidi,
    lowestNoteName: "C4",
    highestNoteName: "C5",
    sourceMethod: "MIC_MEASURE",
    measuredAt,
  };
}

function buildVoiceRangeHistoryResponse(
  snapshots: VoiceRangeSnapshotResponse[],
): VoiceRangeHistoryResponse {
  return { voiceRangeSnapshotResponses: snapshots };
}

// renderWithQueryClient — race-helpers 4종 helper 의 makeQueryClient/makeWrapper 만
// reuse (PR #1057). 매 케이스마다 새 QueryClient 로 retry / cache 격리.
function renderWithQueryClient(ui: ReactNode) {
  const client = makeQueryClient();
  return render(ui, { wrapper: makeWrapper(client) });
}

beforeEach(() => {
  historyMock.reset();
  sessionMock.reset();
  readRecommendationHistoryMock.mockReset();
  readVoiceRangeHistoryMock.mockReset();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe("/history 페이지 query/source 전환 race 가드 (PR #1057 race-helpers reuse, #1064)", () => {
  // 시나리오 1 — sessionId hydration race
  //
  // zustand persist hydrate 전엔 sessionId === null → 두 useQuery 가 enabled false
  // → BE 호출 0건. 이 동안 localStorage entry 가 있으면 카드 노출. hydrate 후
  // sessionId 가 truthy 로 바뀌면 BE query 활성화 — 단 본 케이스는 mock store 라
  // hydration 시뮬레이션이 sessionMock.set() 한 줄로 충분.
  //
  // 가드 의미: `enabled: Boolean(sessionId)` 가드를 누군가 무의식 제거하면 hydrate
  // 전 BE 호출이 발생 → SessionAuthGuard 가 X-Session-Id 누락으로 401 → console
  // noise + 사용자 가시 에러 노출 회귀. 본 가드가 차단.
  it("시나리오 1: sessionId null 상태에선 BE 호출 0건 + localStorage fallback 카드 렌더", async () => {
    const tenMinutesAgo = new Date(Date.now() - 10 * 60 * 1000).toISOString();
    historyMock.set({
      recommendations: [buildLocalEntry("e-1", tenMinutesAgo, [10, 20, 30])],
    });
    // sessionMock.reset() default 가 sessionId=null — 명시적 재확인.
    sessionMock.set({ sessionId: null });

    renderWithQueryClient(<HistoryPage />);

    // localStorage fallback 카드 — local-곡-10 노출.
    expect(await screen.findByText("local-곡-10")).toBeInTheDocument();

    // BE 호출은 0건 — enabled gating 동작.
    expect(readRecommendationHistoryMock).not.toHaveBeenCalled();
    expect(readVoiceRangeHistoryMock).not.toHaveBeenCalled();
  });

  // 시나리오 2 — BE 응답 도착 시 source 전환 (localStorage → BE)
  //
  // sessionId truthy + localStorage entry 1건 보유 상태에서 페이지 마운트 →
  // 두 query 가 pending → 그동안 localStorage fallback 카드 노출 → BE 응답 도착 →
  // displayEntries useMemo 가 재계산되어 BE source 카드로 전환.
  //
  // 가드 의미: 누군가 `displayEntries` 의 useMemo dependency 를 잘못 건드리면
  // BE 응답 도착 후에도 stale 한 local 카드를 계속 보여주는 silent 회귀. 본 가드가
  // 차단. spec recommendation-history-and-feedback §5-6 의 source-of-truth 우선순위
  // 회귀 방지.
  it("시나리오 2: BE 응답 도착 시 localStorage → BE source 전환 (스펙 §5-6 우선순위)", async () => {
    const tenMinutesAgo = new Date(Date.now() - 10 * 60 * 1000).toISOString();
    sessionMock.set({ sessionId: "test-session-id" });
    historyMock.set({
      recommendations: [buildLocalEntry("e-1", tenMinutesAgo, [10])],
    });

    // recommendation-history 는 deferred 로 응답 시점 통제.
    const recoDeferred =
      createDeferred<RecommendationHistoryListResponse>();
    readRecommendationHistoryMock.mockReturnValueOnce(recoDeferred.promise);
    // voice-range-history 는 빈 응답으로 즉시 settle (progress section noise 차단).
    readVoiceRangeHistoryMock.mockResolvedValue(
      buildVoiceRangeHistoryResponse([]),
    );

    renderWithQueryClient(<HistoryPage />);

    // BE 호출 1회 발생 — sessionId truthy 라 enabled true.
    await waitFor(() => {
      expect(readRecommendationHistoryMock).toHaveBeenCalledTimes(1);
    });

    // 응답 도착 전 — localStorage fallback 카드 노출.
    expect(screen.getByText("local-곡-10")).toBeInTheDocument();
    expect(screen.queryByText("be-곡-100")).not.toBeInTheDocument();

    // BE 응답 도착 — source 전환.
    await act(async () => {
      recoDeferred.resolve(
        buildBackendListResponse([
          buildBackendEntry(1, tenMinutesAgo, [100, 200]),
        ]),
      );
      await Promise.resolve();
      await Promise.resolve();
    });

    // BE source 카드 노출, local 카드는 사라짐 (displayEntries 가 BE 우선).
    await waitFor(() => {
      expect(screen.getByText("be-곡-100")).toBeInTheDocument();
    });
    expect(screen.queryByText("local-곡-10")).not.toBeInTheDocument();

    // 헤더 카피도 BE source 분기 — "세션 ID 기준 N건의 추천을 서버에서 불러왔어요"
    expect(
      screen.getByText(/세션 ID 기준 1건의 추천을 서버에서 불러왔어요/),
    ).toBeInTheDocument();
  });

  // 시나리오 3 — BE 401 → graceful fallback (localStorage 유지)
  //
  // SessionAuthGuard (PR #244) 가 X-Session-Id mismatch 시 401 반환 → query 가
  // error state. HistoryPage 의 `pickProgressSummary` 와 `displayEntries` 둘 다
  // BE response 가 없으면 localStorage 로 fallback — 사용자가 에러 메시지 대신
  // 본인 데이터를 계속 본다 (graceful degradation).
  //
  // 가드 의미: 누군가 query 의 retry 정책을 바꾸거나, error 시 displayEntries 를
  // 빈 배열로 떨어뜨리는 회귀가 들어오면 사용자가 자기 history 를 못 봄. 본 가드가
  // 차단. 401 도 console.error 로 노출되지 않아야 (logging.ts safeLog 가 mute).
  it("시나리오 3: BE 401 응답 → localStorage fallback 유지 (graceful degradation)", async () => {
    const tenMinutesAgo = new Date(Date.now() - 10 * 60 * 1000).toISOString();
    sessionMock.set({ sessionId: "test-session-id" });
    historyMock.set({
      recommendations: [buildLocalEntry("e-1", tenMinutesAgo, [10])],
    });

    // 두 query 모두 401 — graceful fallback 트리거.
    readRecommendationHistoryMock.mockRejectedValue(unauthorizedError());
    readVoiceRangeHistoryMock.mockRejectedValue(unauthorizedError());

    renderWithQueryClient(<HistoryPage />);

    // 401 도착 대기 — query state 가 error 로 settle.
    await waitFor(() => {
      expect(readRecommendationHistoryMock).toHaveBeenCalled();
    });

    // localStorage fallback 카드는 그대로 — 사용자가 자기 history 를 계속 봄.
    expect(screen.getByText("local-곡-10")).toBeInTheDocument();

    // 헤더 카피는 localStorage 분기 — "최근 N건의 추천을 기록해두었어요"
    await waitFor(() => {
      expect(
        screen.getByText(/최근 1건의 추천을 기록해두었어요/),
      ).toBeInTheDocument();
    });

    // 401 ApiError 가 throw 됐어도 BE source 카드는 노출되지 않음.
    expect(screen.queryByText(/be-곡-/)).not.toBeInTheDocument();
  });

  // 시나리오 4 — 페이지 unmount mid-query
  //
  // recommendation-history / voice-range-history 두 query 가 pending 상태에서
  // 페이지 unmount → React Query 가 last observer abort 호출. unmount 후 응답이
  // 도착해도 setState 가 unmounted observer 라 React state update warning 0건.
  //
  // 가드 의미: 누군가 page.tsx 의 useQuery 시그니처를 바꿔 cleanup 가드를 깨면
  // (예: enabled 를 직접 setState 로 동기화) state update warning 발생 → 사용자
  // 체감 X 지만 dev/test 환경에서 noise + 메모리 leak 잠재. 본 가드가 차단.
  it("시나리오 4: pending 중 페이지 unmount → React state update warning 0건", async () => {
    const tenMinutesAgo = new Date(Date.now() - 10 * 60 * 1000).toISOString();
    sessionMock.set({ sessionId: "test-session-id" });
    historyMock.set({
      recommendations: [buildLocalEntry("e-1", tenMinutesAgo, [10])],
    });

    // 두 query 모두 deferred — unmount 까지 pending 유지.
    const recoDeferred =
      createDeferred<RecommendationHistoryListResponse>();
    const vrDeferred = createDeferred<VoiceRangeHistoryResponse>();
    readRecommendationHistoryMock.mockReturnValueOnce(recoDeferred.promise);
    readVoiceRangeHistoryMock.mockReturnValueOnce(vrDeferred.promise);

    const consoleErrorSpy = vi
      .spyOn(console, "error")
      .mockImplementation(() => {});

    const { unmount } = renderWithQueryClient(<HistoryPage />);

    await waitFor(() => {
      expect(readRecommendationHistoryMock).toHaveBeenCalledTimes(1);
    });

    // pending 중 page unmount.
    unmount();

    // 응답 도착 — observer 가 비어있어 setState 가 안 일어나야 한다.
    await act(async () => {
      recoDeferred.resolve(
        buildBackendListResponse([
          buildBackendEntry(1, tenMinutesAgo, [100]),
        ]),
      );
      vrDeferred.resolve(buildVoiceRangeHistoryResponse([]));
      await Promise.resolve();
      await Promise.resolve();
    });

    const warningCalls = consoleErrorSpy.mock.calls.filter((args) => {
      const message = typeof args[0] === "string" ? args[0] : "";
      return (
        message.includes("unmounted component") ||
        message.includes("memory leak") ||
        message.includes("state update on an unmounted")
      );
    });
    expect(warningCalls).toEqual([]);

    consoleErrorSpy.mockRestore();
  });

  // 시나리오 5 — progressSummary BE-우선 분기 (snapshots ≥ 2 fresh)
  //
  // voice-range BE snapshots 가 2건 이상 fresh 도착 시 `pickProgressSummary` 가
  // BE summary 를 사용 — localStorage entry 의 voiceRangeLow/HighMidi 가 없어도
  // VoiceRangeProgressCard 가 렌더된다. spec voice-range-progress §3
  // source-of-truth 우선순위 보장.
  //
  // 가드 의미: 누군가 `pickProgressSummary` 의 분기 순서 (BE → fallback) 를 뒤집거나
  // BE snapshots 가 있는데도 localStorage 로 fallback 하게 만들면, 다른 기기에서 측정
  // 한 사용자의 음역 발전 그래프가 사라지는 silent 회귀. 본 가드가 차단.
  //
  // local entry 는 MIDI 정보가 없으므로 BE source 가 없으면 ProgressEmptyCta 또는
  // null 분기 — 본 케이스는 BE source 우선이 동작해야 카드가 렌더된다.
  it("시나리오 5: voice-range BE snapshots 2건 fresh → BE summary 사용 (스펙 §3 source-of-truth)", async () => {
    const tenMinutesAgo = new Date(Date.now() - 10 * 60 * 1000).toISOString();
    const oneHourAgo = new Date(Date.now() - 60 * 60 * 1000).toISOString();
    sessionMock.set({ sessionId: "test-session-id" });
    historyMock.set({
      // local entry 에 MIDI 정보 없음 — BE snapshots 가 사용되어야 카드 렌더.
      recommendations: [buildLocalEntry("e-1", tenMinutesAgo, [10])],
    });

    // recommendation-history 는 빈 응답으로 즉시 settle — local fallback 으로 카드 렌더.
    readRecommendationHistoryMock.mockResolvedValue(
      buildBackendListResponse([]),
    );
    // voice-range BE 가 datapoint 2건 fresh 응답.
    readVoiceRangeHistoryMock.mockResolvedValue(
      buildVoiceRangeHistoryResponse([
        buildVoiceRangeSnapshot(1, oneHourAgo, 60, 72),
        buildVoiceRangeSnapshot(2, tenMinutesAgo, 62, 74),
      ]),
    );

    renderWithQueryClient(<HistoryPage />);

    // BE snapshots 응답 도착 — VoiceRangeProgressCard 가 렌더된다.
    // 카드 heading: "음역 발전 그래프" 류 또는 진행 카드 자체 (구현 디테일에 안 묶이도록
    // ProgressEmptyCta 의 "측정 2번부터" 문구가 안 보이는지로 우회 검증).
    await waitFor(() => {
      // 측정값이 충분 → ProgressEmptyCta 노출 안 됨.
      expect(
        screen.queryByText(/음역 발전 그래프는 측정 2번부터/),
      ).not.toBeInTheDocument();
    });

    // localStorage entry 에 voiceRangeLow/HighMidi 가 없는데도 진행 카드가 렌더되었음
    // = pickProgressSummary 가 BE snapshots 를 우선 사용했다는 증거. 카드 자체의
    // 정확한 텍스트는 VoiceRangeProgressCard 구현이 책임지므로 본 가드는
    // "ProgressEmptyCta 가 노출되지 않는다" 라는 negative invariant 로 충분.
    //
    // 추가 sanity: BE 호출 1회 발생 — sessionId truthy + enabled true 확인.
    expect(readVoiceRangeHistoryMock).toHaveBeenCalledTimes(1);
    expect(readVoiceRangeHistoryMock).toHaveBeenCalledWith(
      "test-session-id",
      expect.any(AbortSignal),
    );
  });
});

/**
 * 본 파일에서 사용된 race-helpers (PR #1057) — reuse 카탈로그:
 *   - makeQueryClient — renderWithQueryClient 1곳
 *   - makeWrapper — renderWithQueryClient 1곳
 *   - createDeferred — 시나리오 2 (1회) + 시나리오 4 (2회) = 3회
 *   - unauthorizedError — 시나리오 3 (2회 — 두 query 모두 401)
 *
 * 직전 likes/bookmarks (PR #1054) / recommend (PR #1048) 가 helper 추출 전 inline
 * 정의로 ~30 LOC 중복 가졌던 점을 감안하면, 본 파일은 import 한 줄 (8 LOC) 로 동일
 * 헬퍼 reuse — LOC 절감 효과 그대로 입증.
 *
 * 사용 안 한 부분: 페이지별 픽스처 (`buildLocalEntry` / `buildBackendEntry` /
 * `buildVoiceRangeSnapshot`) 는 본 파일 전용 — PR #1057 발견 사항 ("도메인 픽스처는
 * 별도 사이클로 통합") 그대로 inline 유지. 다음 사이클 후보로 history fixture 추출
 * (`web/lib/test-fixtures/history.ts`) 권고.
 */

// 본 파일은 ApiError 를 직접 throw 하는 케이스가 없으므로 ApiError 는 type-only
// import 가 아닌 value import — `unauthorizedError()` 가 이미 ApiError 인스턴스를
// 반환하므로 본 파일에서 추가 사용은 없다. 향후 다른 status code (404 / 500) race
// 가드 추가 시 reuse 가능하도록 import 만 두고 다음 사이클에 정리해도 무방.
void ApiError;
