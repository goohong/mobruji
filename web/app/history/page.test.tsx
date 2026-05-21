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

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import HistoryPage from "./page";
import { expectNoA11yViolations } from "@/lib/test-helpers/a11y";
import type {
  RecommendationHistoryEntry,
  RecommendationHistoryInput,
} from "@/store/history";

const { historyMock } = await vi.hoisted(async () => {
  const helper = await import("@/lib/test-helpers/mock-history-store");
  return { historyMock: helper.buildHistoryStoreMock() };
});

vi.mock("@/store/history", async () => {
  const actual =
    await vi.importActual<typeof import("@/store/history")>("@/store/history");
  return {
    ...actual,
    useHistoryStore: historyMock.useHistoryStore,
  };
});

function buildEntry(
  id: string,
  requestedAt: string,
  songIds: number[],
  overrides: Partial<RecommendationHistoryEntry> = {},
): RecommendationHistoryEntry {
  return {
    id,
    requestedAt,
    requestId: parseInt(id.replace(/\D/g, ""), 10) || 1,
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
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("HistoryPage", () => {
  it("빈 상태에서 안내 문구와 음역대 입력 CTA를 렌더한다", () => {
    render(<HistoryPage />);

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

    render(<HistoryPage />);

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

    render(<HistoryPage />);

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

    render(<HistoryPage />);

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

    render(<HistoryPage />);
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

    render(<HistoryPage />);
    await user.click(screen.getByRole("button", { name: "전체 삭제" }));

    expect(historyMock.state().clearHistory).not.toHaveBeenCalled();

    vi.unstubAllGlobals();
  });

  describe("a11y", () => {
    it("빈 상태에 a11y 위반이 없다", async () => {
      const { container } = render(<HistoryPage />);
      await expectNoA11yViolations(container);
    });

    it("히스토리 카드 리스트 상태에 a11y 위반이 없다", async () => {
      const tenMinutesAgo = new Date(
        Date.now() - 10 * 60 * 1000,
      ).toISOString();
      const input: RecommendationHistoryInput = {
        voiceRangeId: 1,
        requestId: 1,
        songs: buildEntry("e-1", tenMinutesAgo, [1, 2, 3, 4]).songs,
        excludedSongIds: [99],
      };
      historyMock.set({
        recommendations: [
          { id: "e-1", requestedAt: tenMinutesAgo, ...input },
        ],
      });

      const { container } = render(<HistoryPage />);
      await expectNoA11yViolations(container);
    });
  });
});
