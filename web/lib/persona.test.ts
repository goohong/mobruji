/**
 * 페르소나 UI 헬퍼 단위 가드 (이슈 #1600).
 *
 * - personaReasonLabel: P-E 는 "안심 포인트", 그 외는 중립 라벨.
 * - buildPersonaFallbackReason: P-E + 곡 난이도 기반 client fallback 사유.
 * - resolvePersonaReason: BE personaReason 우선 → 없으면 fallback → 둘 다 없으면 null.
 */

import { describe, expect, it } from "vitest";

import type { RecommendedSongResponse } from "@/lib/api/recommendation";
import {
  buildPersonaFallbackReason,
  personaReasonLabel,
  resolvePersonaReason,
  SAFE_SONG_PERSONA,
} from "./persona";

function buildItem(
  overrides: Partial<RecommendedSongResponse> = {},
  songOverrides: Partial<RecommendedSongResponse["song"]> = {},
): RecommendedSongResponse {
  return {
    song: {
      id: 1,
      title: "테스트 곡",
      artist: "테스트 아티스트",
      releaseYear: 2020,
      keyOriginal: "C_MAJOR",
      bpm: 90,
      mood: "CALM",
      language: "ko",
      genre: "발라드",
      tjNumber: null,
      kyNumber: null,
      metadataSource: "MANUAL_SEED",
      lowMidi: 50,
      highMidi: 60,
      difficulty: "EASY",
      ...songOverrides,
    },
    score: 0.9,
    matchReason: "음역대가 잘 맞아요",
    rankPosition: 1,
    ...overrides,
  };
}

describe("personaReasonLabel", () => {
  it("P-E 는 '안심 포인트' 라벨을 돌려준다", () => {
    expect(personaReasonLabel("P-E")).toBe("안심 포인트");
  });

  it("그 외 페르소나는 중립 라벨을 돌려준다", () => {
    expect(personaReasonLabel("P-A")).toBe("이 곡을 고른 이유");
  });
});

describe("buildPersonaFallbackReason", () => {
  it("P-E + EASY 곡은 안심 사유를 만든다", () => {
    const song = buildItem({}, { difficulty: "EASY" }).song;
    expect(buildPersonaFallbackReason(SAFE_SONG_PERSONA, song)).toContain(
      "부담 없이",
    );
  });

  it("P-E + 난이도 미상 곡은 null 을 돌려준다 (사유 생략)", () => {
    const song = buildItem(
      {},
      { difficulty: null, lowMidi: null, highMidi: null },
    ).song;
    expect(buildPersonaFallbackReason(SAFE_SONG_PERSONA, song)).toBeNull();
  });

  it("P-E 가 아닌 페르소나는 fallback 사유를 만들지 않는다", () => {
    const song = buildItem({}, { difficulty: "EASY" }).song;
    expect(buildPersonaFallbackReason("P-A", song)).toBeNull();
  });
});

describe("resolvePersonaReason", () => {
  it("BE personaReason 이 있으면 그 값을 우선한다", () => {
    const item = buildItem({
      persona: "P-E",
      personaReason: "BE 가 내려준 안심 사유",
    });
    expect(resolvePersonaReason(item, "P-E")).toEqual({
      label: "안심 포인트",
      text: "BE 가 내려준 안심 사유",
    });
  });

  it("BE personaReason 이 없으면 활성 페르소나 + 난이도 기반 fallback 을 쓴다", () => {
    const item = buildItem({}, { difficulty: "EASY" });
    const result = resolvePersonaReason(item, "P-E");
    expect(result?.label).toBe("안심 포인트");
    expect(result?.text).toContain("부담 없이");
  });

  it("활성 페르소나가 없고 BE persona 도 없으면 null (사유 줄 생략)", () => {
    const item = buildItem({}, { difficulty: "EASY" });
    expect(resolvePersonaReason(item, null)).toBeNull();
  });

  it("BE persona 가 응답에 있으면 활성 페르소나가 null 이어도 사유를 노출한다", () => {
    const item = buildItem(
      { persona: "P-E", personaReason: "BE 안심 사유" },
      {},
    );
    expect(resolvePersonaReason(item, null)).toEqual({
      label: "안심 포인트",
      text: "BE 안심 사유",
    });
  });
});
