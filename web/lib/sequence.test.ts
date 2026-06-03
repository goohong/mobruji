/**
 * P-D 시퀀스 헬퍼 단위 가드 (이슈 #1601).
 *
 * - nextStage: 도입→고조→마무리 진행, 마지막은 null.
 * - stageMeta: 단계별 라벨/설명.
 * - deriveSequenceFallback: 단일 추천을 3단계로 결정성 있게 분배 + persona=P-D.
 */

import { describe, expect, it } from "vitest";

import type {
  RecommendationResponse,
  RecommendedSongResponse,
} from "@/lib/api/recommendation";
import {
  deriveSequenceFallback,
  HOST_PERSONA,
  nextStage,
  SEQUENCE_STAGE_ORDER,
  stageMeta,
} from "./sequence";

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

function baseResponse(ids: number[]): RecommendationResponse {
  return {
    requestId: "01933b1c-7f8a-7c2d-9b3e-0123456789ab",
    recommendations: ids.map(song),
  };
}

describe("nextStage", () => {
  it("도입→고조→마무리 순서로 진행한다", () => {
    expect(nextStage("INTRO")).toBe("PEAK");
    expect(nextStage("PEAK")).toBe("FINALE");
  });

  it("마지막 단계(마무리)에서는 null 을 돌려준다", () => {
    expect(nextStage("FINALE")).toBeNull();
  });
});

describe("stageMeta", () => {
  it("단계별 한국어 라벨을 돌려준다", () => {
    expect(stageMeta("INTRO").label).toBe("도입");
    expect(stageMeta("PEAK").label).toBe("고조");
    expect(stageMeta("FINALE").label).toBe("마무리");
  });
});

describe("deriveSequenceFallback", () => {
  it("9곡을 3단계로 균등 분배하고 persona=P-D 로 표시한다", () => {
    const result = deriveSequenceFallback(
      baseResponse([1, 2, 3, 4, 5, 6, 7, 8, 9]),
    );

    expect(result.persona).toBe(HOST_PERSONA);
    expect(result.stages.map((s) => s.stage)).toEqual(SEQUENCE_STAGE_ORDER);
    expect(result.stages[0].songs.map((s) => s.song.id)).toEqual([1, 2, 3]);
    expect(result.stages[1].songs.map((s) => s.song.id)).toEqual([4, 5, 6]);
    expect(result.stages[2].songs.map((s) => s.song.id)).toEqual([7, 8, 9]);
  });

  it("나머지 곡은 앞 단계부터 1곡씩 더 배정한다 (결정성)", () => {
    // 5곡 → 2/2/1 (나머지 2곡이 INTRO, PEAK 로).
    const result = deriveSequenceFallback(baseResponse([1, 2, 3, 4, 5]));
    expect(result.stages[0].songs.map((s) => s.song.id)).toEqual([1, 2]);
    expect(result.stages[1].songs.map((s) => s.song.id)).toEqual([3, 4]);
    expect(result.stages[2].songs.map((s) => s.song.id)).toEqual([5]);
  });

  it("곡이 3개 미만이면 일부 단계는 빈 묶음이 된다", () => {
    const result = deriveSequenceFallback(baseResponse([1]));
    expect(result.stages[0].songs.map((s) => s.song.id)).toEqual([1]);
    expect(result.stages[1].songs).toEqual([]);
    expect(result.stages[2].songs).toEqual([]);
  });

  it("빈 추천이면 모든 단계가 빈 묶음이고 requestId 는 보존된다", () => {
    const result = deriveSequenceFallback(baseResponse([]));
    expect(result.requestId).toBe("01933b1c-7f8a-7c2d-9b3e-0123456789ab");
    expect(result.stages.every((s) => s.songs.length === 0)).toBe(true);
  });
});
