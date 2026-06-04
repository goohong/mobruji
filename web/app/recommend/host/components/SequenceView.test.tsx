/**
 * SequenceView 컴포넌트 테스트 (이슈 #1601).
 *
 * - 최초엔 워밍업 단계 곡 + 단계 설명(BE stageReason)을 보여준다.
 * - "다음 (고조)으로" 진행 버튼 클릭 시 고조 단계로 흐른다.
 * - 단계 토글로 특정 단계로 점프할 수 있다.
 * - 마지막(마무리) 단계에서는 진행 버튼 대신 마무리 안내가 보인다.
 * - 빈 단계는 "추천할 곡이 없어요" 안내를 보여준다.
 * - a11y 위반 없음.
 */

import { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { expectNoA11yViolations } from "@/lib/test-helpers/a11y";
import type {
  Mood,
  RecommendedSongResponse,
  SequenceStage,
  SequenceStageBundle,
} from "@/lib/api/recommendation";

vi.mock("@/lib/api/feedback", () => ({
  toggleLike: vi.fn(),
  toggleBookmark: vi.fn(),
}));

import { SequenceView } from "./SequenceView";

afterEach(() => cleanup());

function song(id: number): RecommendedSongResponse {
  return {
    rankPosition: id,
    score: 0.9,
    matchReason: "음역 매칭",
    song: {
      id,
      title: `곡-${id}`,
      artist: "가수",
      releaseYear: 2024,
      keyOriginal: "C_MAJOR",
      bpm: 110,
      mood: "UPBEAT",
      language: "ko",
      genre: "POP",
      tjNumber: `T-${id}`,
      kyNumber: `K-${id}`,
      metadataSource: "MANUAL_SEED",
    },
  };
}

function bundle(
  stage: SequenceStage,
  mood: Mood,
  recommendations: RecommendedSongResponse[],
): SequenceStageBundle {
  return {
    stage,
    mood,
    stageReason: `${stage} 단계 설명`,
    requestId: 1,
    relaxed: false,
    relaxedFilters: [],
    recommendations,
  };
}

function renderView(stages: SequenceStageBundle[]) {
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
  return render(
    <SequenceView
      stages={stages}
      userVoiceRange={{ lowMidi: 48, highMidi: 72 }}
    />,
    { wrapper: Wrapper },
  );
}

const fullStages: SequenceStageBundle[] = [
  bundle("WARMUP", "CALM", [song(1)]),
  bundle("PEAK", "UPBEAT", [song(2)]),
  bundle("CLOSING", "EMOTIONAL", [song(3)]),
];

describe("SequenceView", () => {
  it("최초엔 워밍업 단계 곡과 BE 단계 설명을 보여준다", () => {
    renderView(fullStages);
    expect(
      screen.getByRole("heading", { name: "워밍업", level: 2 }),
    ).toBeInTheDocument();
    expect(screen.getByText("WARMUP 단계 설명")).toBeInTheDocument();
    expect(screen.getByText("곡-1")).toBeInTheDocument();
    // 고조 단계 곡은 아직 안 보인다.
    expect(screen.queryByText("곡-2")).not.toBeInTheDocument();
  });

  it("진행 버튼 클릭 시 다음 단계(고조)로 흐른다", async () => {
    const user = userEvent.setup();
    renderView(fullStages);

    await user.click(screen.getByRole("button", { name: /다음 \(고조\)으로/ }));

    expect(
      screen.getByRole("heading", { name: "고조", level: 2 }),
    ).toBeInTheDocument();
    expect(screen.getByText("곡-2")).toBeInTheDocument();
  });

  it("단계 토글로 특정 단계(마무리)로 점프할 수 있다", async () => {
    const user = userEvent.setup();
    renderView(fullStages);

    await user.click(screen.getByRole("radio", { name: /마무리/ }));

    expect(
      screen.getByRole("heading", { name: "마무리", level: 2 }),
    ).toBeInTheDocument();
    expect(screen.getByText("곡-3")).toBeInTheDocument();
  });

  it("마지막(마무리) 단계에서는 진행 버튼 대신 마무리 안내가 보인다", async () => {
    const user = userEvent.setup();
    renderView(fullStages);

    await user.click(screen.getByRole("radio", { name: /마무리/ }));

    expect(
      screen.queryByRole("button", { name: /다음 \(/ }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByText(/마무리까지 흐름이 끝났어요/),
    ).toBeInTheDocument();
  });

  it("빈 단계는 추천할 곡이 없다는 안내를 보여준다", async () => {
    const user = userEvent.setup();
    renderView([
      bundle("WARMUP", "CALM", [song(1)]),
      bundle("PEAK", "UPBEAT", []),
      bundle("CLOSING", "EMOTIONAL", [song(3)]),
    ]);

    await user.click(screen.getByRole("radio", { name: /고조/ }));
    expect(
      screen.getByText(/이 단계에 추천할 곡이 없어요/),
    ).toBeInTheDocument();
  });

  it("a11y 위반이 없다", async () => {
    const { container } = renderView(fullStages);
    await expectNoA11yViolations(container);
  });
});
