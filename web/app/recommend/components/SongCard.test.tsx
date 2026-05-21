/**
 * SongCard 렌더 테스트.
 *
 * - 응답에 `difficulty`가 있으면 그 값을 라벨로 표시한다 (BE 우선).
 * - `difficulty`가 없고 `lowMidi`/`highMidi`만 있으면 client-side `deriveDifficulty`로 분류한다.
 * - 최고음/최저음 음표명, 장르 칩, score, matchReason이 노출된다.
 */

import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

import { SongCard } from "./SongCard";
import { expectNoA11yViolations } from "@/lib/test-helpers/a11y";
import type { RecommendedSongResponse } from "@/lib/api/recommendation";

function buildItem(
  overrides: Partial<RecommendedSongResponse["song"]> = {},
  itemOverrides: Partial<RecommendedSongResponse> = {},
): RecommendedSongResponse {
  return {
    rankPosition: 1,
    score: 0.91,
    matchReason: "음역 매칭",
    song: {
      id: 1,
      title: "테스트 곡",
      artist: "가수",
      releaseYear: 2024,
      keyOriginal: "C_MAJOR",
      bpm: 120,
      mood: "UPBEAT",
      language: "ko",
      genre: "POP",
      tjNumber: null,
      kyNumber: null,
      metadataSource: "MANUAL_SEED",
      ...overrides,
    },
    ...itemOverrides,
  };
}

afterEach(() => {
  cleanup();
});

describe("SongCard", () => {
  it("BE 응답에 difficulty가 있으면 그 값을 라벨로 노출한다", () => {
    const item = buildItem({ difficulty: "HARD" });
    render(
      <ul>
        <SongCard item={item} />
      </ul>,
    );
    expect(
      screen.getByLabelText(/가창 난이도 Hard/),
    ).toBeInTheDocument();
    expect(screen.getByText("테스트 곡")).toBeInTheDocument();
    expect(screen.getByText("가수")).toBeInTheDocument();
    expect(screen.getByText("POP")).toBeInTheDocument();
    expect(screen.getByText(/score 0\.91/)).toBeInTheDocument();
  });

  it("difficulty가 없고 lowMidi/highMidi만 있으면 client-side 계산 라벨을 노출한다", () => {
    // highMidi=77(F5) → HARD
    const item = buildItem({ lowMidi: 55, highMidi: 77 });
    render(
      <ul>
        <SongCard item={item} />
      </ul>,
    );
    expect(
      screen.getByLabelText(/가창 난이도 Hard/),
    ).toBeInTheDocument();
    // 최고음 음표명 노출 — MIDI 77 = F5
    expect(screen.getByLabelText(/최고음 F5/)).toBeInTheDocument();
    // 최저음(작게) — MIDI 55 = G3
    expect(screen.getByText("G3")).toBeInTheDocument();
  });

  it("난이도 정보가 전혀 없으면 난이도 라벨을 숨기되 나머지는 정상 노출", () => {
    const item = buildItem();
    render(
      <ul>
        <SongCard item={item} />
      </ul>,
    );
    expect(screen.queryByLabelText(/가창 난이도/)).not.toBeInTheDocument();
    // 카드 자체는 렌더됨
    expect(screen.getByText("테스트 곡")).toBeInTheDocument();
    expect(screen.getByText("C Major")).toBeInTheDocument();
  });

  // closes #91 #92 — 검색 페이지에서 song prop으로 카드 렌더 시
  // rank/score/matchReason은 숨기고 곡 정보만 노출한다.
  it("song prop만 받으면 rank/score/matchReason은 숨기고 곡 정보만 보여준다", () => {
    const item = buildItem({ lowMidi: 48, highMidi: 78 }); // HARD
    render(
      <ul>
        <SongCard song={item.song} />
      </ul>,
    );
    expect(screen.getByText("테스트 곡")).toBeInTheDocument();
    expect(screen.getByText("가수")).toBeInTheDocument();
    expect(screen.getByLabelText(/가창 난이도 Hard/)).toBeInTheDocument();
    // 추천 컨텍스트 전용 표시는 모두 숨김.
    expect(screen.queryByText(/score/)).not.toBeInTheDocument();
    expect(screen.queryByText(/음역 매칭/)).not.toBeInTheDocument();
    expect(screen.queryByText("#1")).not.toBeInTheDocument();
  });

  // closes #100 — href가 주어지면 카드 전체가 곡 상세 페이지로 가는 링크가 된다.
  it("href가 주어지면 카드 전체를 상세 페이지 링크로 감싼다", () => {
    const item = buildItem({ difficulty: "NORMAL" });
    render(
      <ul>
        <SongCard item={item} href="/songs/1" />
      </ul>,
    );
    const link = screen.getByRole("link", { name: /테스트 곡 상세 보기/ });
    expect(link).toHaveAttribute("href", "/songs/1");
    // 카드 내용은 그대로 보여야 한다.
    expect(screen.getByText("테스트 곡")).toBeInTheDocument();
    expect(screen.getByText("#1")).toBeInTheDocument();
  });

  // closes #107 — axe-core 자동 검사. serious/critical 위반이 없어야 한다.
  // SongCard는 다양한 prop 조합으로 렌더되므로 대표 케이스 4종을 모두 검사.
  describe("a11y", () => {
    it("추천 컨텍스트 카드는 a11y 위반이 없다 (item + difficulty)", async () => {
      const item = buildItem({ difficulty: "HARD", lowMidi: 55, highMidi: 77 });
      const { container } = render(
        <ul>
          <SongCard item={item} />
        </ul>,
      );
      await expectNoA11yViolations(container);
    });

    it("검색 컨텍스트 카드는 a11y 위반이 없다 (song prop)", async () => {
      const item = buildItem({ difficulty: "NORMAL" });
      const { container } = render(
        <ul>
          <SongCard song={item.song} />
        </ul>,
      );
      await expectNoA11yViolations(container);
    });

    it("href 링크 카드는 a11y 위반이 없다", async () => {
      const item = buildItem({ difficulty: "EASY" });
      const { container } = render(
        <ul>
          <SongCard item={item} href="/songs/1" />
        </ul>,
      );
      await expectNoA11yViolations(container);
    });

    it("난이도 정보가 없는 카드도 a11y 위반이 없다", async () => {
      const item = buildItem();
      const { container } = render(
        <ul>
          <SongCard item={item} />
        </ul>,
      );
      await expectNoA11yViolations(container);
    });
  });
});
