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
});
