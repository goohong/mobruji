/**
 * scoreBreakdown helper 단위 테스트 (closes #141).
 *
 * 검증 포인트:
 *   1) backend가 `breakdown` 필드를 응답에 포함하면 그 값을 우선 사용(추정 미수행).
 *   2) `breakdown` 없으면 client-side 추정. 사용자 voiceRange가 주어졌을 때
 *      keyMatch/rangeFit/genreMatch 3종이 점수 내림차순으로 반환된다.
 *   3) 사용자 voiceRange가 없거나 곡 음역 정보가 없으면 rangeFit 항목은 생략.
 */

import { describe, expect, it } from "vitest";

import type {
  RecommendedSongResponse,
  SongResponse,
} from "@/lib/api/recommendation";
import {
  buildScoreBreakdown,
  type RecommendationBreakdownItem,
} from "./scoreBreakdown";

function buildSong(overrides: Partial<SongResponse> = {}): SongResponse {
  return {
    id: 1,
    title: "테스트 곡",
    artist: "가수",
    releaseYear: 2024,
    keyOriginal: "C_SHARP_MAJOR",
    bpm: 120,
    mood: "EMOTIONAL",
    language: "ko",
    genre: "발라드",
    tjNumber: null,
    kyNumber: null,
    metadataSource: "MANUAL_SEED",
    lowMidi: 55, // G3
    highMidi: 77, // F5
    difficulty: "HARD",
    ...overrides,
  };
}

function buildItem(
  songOverrides: Partial<SongResponse> = {},
  itemOverrides: Partial<RecommendedSongResponse> = {},
): RecommendedSongResponse {
  return {
    song: buildSong(songOverrides),
    score: 0.87,
    matchReason: "음역 매칭",
    rankPosition: 1,
    ...itemOverrides,
  };
}

describe("buildScoreBreakdown", () => {
  it("응답에 breakdown 필드가 있으면 그 값을 그대로 사용하고 estimated=false로 표기한다", () => {
    const providedBreakdown: RecommendationBreakdownItem[] = [
      {
        key: "keyMatch",
        label: "키 매칭",
        score: 0.95,
        detail: "BE 계산",
        estimated: true, // 입력이 잘못 true여도 강제로 false 처리
      },
    ];
    const itemWithBreakdown = {
      ...buildItem(),
      breakdown: providedBreakdown,
    } as RecommendedSongResponse;

    const breakdown = buildScoreBreakdown(itemWithBreakdown, {
      lowMidi: 48,
      highMidi: 67,
    });

    expect(breakdown).toHaveLength(1);
    expect(breakdown[0]?.key).toBe("keyMatch");
    expect(breakdown[0]?.detail).toBe("BE 계산");
    expect(breakdown[0]?.estimated).toBe(false);
  });

  it("breakdown 없으면 voiceRange 기반으로 key/rangeFit/genre 3종을 점수 내림차순으로 추정한다", () => {
    // 사용자 C3(48)~G4(67), 곡 G3(55)~F5(77) — 겹침은 G3~G4 = 12반음, 곡 span 22반음 → ratio ≈ 0.55
    const item = buildItem();
    const breakdown = buildScoreBreakdown(item, {
      lowMidi: 48,
      highMidi: 67,
    });

    expect(breakdown.map((b) => b.key)).toEqual([
      "keyMatch",
      "genreMatch",
      "rangeFit",
    ]);
    const rangeFit = breakdown.find((b) => b.key === "rangeFit");
    expect(rangeFit?.score).toBeCloseTo(0.55, 1);
    // 이슈 #318: 한국어 (SPN) 병기.
    expect(rangeFit?.detail).toBe(
      "사용자 도3 (C3)-솔4 (G4) vs 곡 솔3 (G3)-파5 (F5)",
    );
    // 모든 추정 항목은 estimated=true
    expect(breakdown.every((b) => b.estimated)).toBe(true);
    // 키 한글 변환 — C_SHARP_MAJOR → "C# Major"
    const keyMatch = breakdown.find((b) => b.key === "keyMatch");
    expect(keyMatch?.detail).toBe("C# Major");
  });

  it("사용자 voiceRange가 없거나 곡 음역 정보가 없으면 rangeFit 항목이 빠진다", () => {
    // voiceRange 미제공 — rangeFit 생략
    const withoutUserRange = buildScoreBreakdown(buildItem(), null);
    expect(withoutUserRange.some((b) => b.key === "rangeFit")).toBe(false);
    expect(withoutUserRange.some((b) => b.key === "keyMatch")).toBe(true);

    // 곡 음역 미제공 — rangeFit 생략
    const itemNoSongRange = buildItem({ lowMidi: null, highMidi: null });
    const withoutSongRange = buildScoreBreakdown(itemNoSongRange, {
      lowMidi: 48,
      highMidi: 67,
    });
    expect(withoutSongRange.some((b) => b.key === "rangeFit")).toBe(false);

    // 키/장르가 비어있으면 점수 0이지만 항목은 남아 사용자에게 "정보 없음"을 알린다
    const itemBlank = buildItem({ keyOriginal: "UNKNOWN", genre: null });
    const blank = buildScoreBreakdown(itemBlank, null);
    const blankKey = blank.find((b) => b.key === "keyMatch");
    const blankGenre = blank.find((b) => b.key === "genreMatch");
    expect(blankKey?.score).toBe(0);
    expect(blankKey?.detail).toBe("키 정보 없음");
    expect(blankGenre?.score).toBe(0);
    expect(blankGenre?.detail).toBe("장르 정보 없음");
  });
});
