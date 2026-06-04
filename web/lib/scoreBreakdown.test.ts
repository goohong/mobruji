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
  computeVoiceFitRatio,
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
    // 한국어 단독 표기 (#1310 사용자 정정 2026-06-03 — SPN 병기 #318 폐지).
    expect(rangeFit?.detail).toBe(
      "사용자 도3-솔4 vs 곡 솔3-파5",
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

describe("buildScoreBreakdown — rangeFit 클램프/정규화 가드 (#639)", () => {
  it("겹침 없음 → 0 클램프 / 완전 포함 → 1 상한 / low>high 역전 → 0 / zero-span 가드", () => {
    const item = buildItem();
    // 곡 55-77, 사용자 30-40 → overlap 음수 → 0
    const noOverlap = buildScoreBreakdown(item, { lowMidi: 30, highMidi: 40 });
    expect(noOverlap.find((b) => b.key === "rangeFit")?.score).toBe(0);
    // 사용자 0-127 → ratio 1 상한
    const full = buildScoreBreakdown(item, { lowMidi: 0, highMidi: 127 });
    expect(full.find((b) => b.key === "rangeFit")?.score).toBe(1);
    // low > high 역전 → 음수 overlap → 0
    const inverted = buildScoreBreakdown(item, { lowMidi: 80, highMidi: 40 });
    expect(inverted.find((b) => b.key === "rangeFit")?.score).toBe(0);
    // zero-span: songSpan = max(1, 0) 가드 → 유한값. overlap=0/songSpan=1 = 0
    // (현재 구현 동작 잠금). 사용자 범위 안에 들었어도 single-note 곡은 0 으로
    // 평가됨 — 명세 변경(single-note=1) 시 즉시 가시화 (#745).
    const zero = buildScoreBreakdown(buildItem({ lowMidi: 60, highMidi: 60 }), {
      lowMidi: 48,
      highMidi: 72,
    });
    const zeroScore = zero.find((b) => b.key === "rangeFit")?.score;
    expect(Number.isFinite(zeroScore ?? NaN)).toBe(true);
    expect(zeroScore).toBe(0);
  });

  it("BE-provided breakdown score 는 0/1 boundary 및 범위 밖 값도 변형 없이 통과 (BE 책임 lock-in)", () => {
    const provided: RecommendationBreakdownItem[] = [
      { key: "keyMatch", label: "k", score: 0, detail: "lo", estimated: true },
      { key: "rangeFit", label: "r", score: 1, detail: "hi", estimated: true },
      { key: "popularity", label: "p", score: 1.5, detail: "over", estimated: true },
    ];
    const item = { ...buildItem(), breakdown: provided } as RecommendedSongResponse;
    const out = buildScoreBreakdown(item, null);
    expect(out.map((b) => b.score)).toEqual([0, 1, 1.5]);
    expect(out.every((b) => b.estimated === false)).toBe(true);
  });
});

// closes #1721 — /songs 검색 카드 "내 음역 적합" 배지가 쓰는 겹침 비율 헬퍼.
// rangeFit breakdown 과 같은 직관(overlap / songSpan)을 공유해야 한다.
describe("computeVoiceFitRatio", () => {
  it("곡 음역이 사용자 음역 안에 완전히 들면 1", () => {
    expect(computeVoiceFitRatio({ lowMidi: 48, highMidi: 72 }, 55, 65)).toBe(1);
  });

  it("겹치는 구간이 없으면 0", () => {
    expect(computeVoiceFitRatio({ lowMidi: 48, highMidi: 55 }, 60, 72)).toBe(0);
  });

  it("부분 겹침은 겹침 반음 / 곡 span 비율", () => {
    // 곡 60-72(span 12), 사용자 60-66 → overlap 6 → 0.5
    expect(computeVoiceFitRatio({ lowMidi: 60, highMidi: 66 }, 60, 72)).toBe(
      0.5,
    );
  });

  it("songSpan 0(단일음) 가드로 유한값을 반환", () => {
    const ratio = computeVoiceFitRatio({ lowMidi: 48, highMidi: 72 }, 60, 60);
    expect(Number.isFinite(ratio)).toBe(true);
    expect(ratio).toBe(0);
  });
});
