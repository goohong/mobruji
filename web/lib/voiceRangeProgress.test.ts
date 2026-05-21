/**
 * extractVoiceRangeProgress 단위 테스트 (closes #170).
 */

import { describe, expect, it } from "vitest";

import { extractVoiceRangeProgress } from "./voiceRangeProgress";
import type { RecommendationHistoryEntry } from "@/store/history";

function buildEntry(
  id: string,
  requestedAt: string,
  overrides: Partial<RecommendationHistoryEntry> = {},
): RecommendationHistoryEntry {
  return {
    id,
    requestedAt,
    requestId: 1,
    voiceRangeId: 42,
    songs: [],
    excludedSongIds: [],
    ...overrides,
  };
}

describe("extractVoiceRangeProgress", () => {
  it("datapoint 가 0~1개면 null 을 반환한다 (빈 상태 UI 분기)", () => {
    expect(extractVoiceRangeProgress([])).toBeNull();
    expect(
      extractVoiceRangeProgress([
        buildEntry("a", "2026-05-21T10:00:00Z", {
          voiceRangeLowMidi: 48,
          voiceRangeHighMidi: 69,
        }),
      ]),
    ).toBeNull();
  });

  it("MIDI 필드가 없는 (legacy) entry 는 datapoint 에서 제외된다", () => {
    const result = extractVoiceRangeProgress([
      // 최신: MIDI 있음.
      buildEntry("new", "2026-05-21T12:00:00Z", {
        voiceRangeLowMidi: 50,
        voiceRangeHighMidi: 72,
      }),
      // legacy: MIDI 없음 — 제외.
      buildEntry("legacy", "2026-05-21T11:00:00Z"),
      // 오래된: MIDI 있음.
      buildEntry("old", "2026-05-21T10:00:00Z", {
        voiceRangeLowMidi: 48,
        voiceRangeHighMidi: 69,
      }),
    ]);

    expect(result).not.toBeNull();
    // 2개만 살아남고, 시간 오름차순(과거 → 현재) 정규화.
    expect(result!.points.map((p) => p.id)).toEqual(["old", "new"]);
  });

  it("min/max + 음역폭 변화(spanDelta)를 계산한다", () => {
    // 최신: 50~74 (span 24), 오래된: 52~70 (span 18). 넓어진 케이스.
    const result = extractVoiceRangeProgress([
      buildEntry("recent", "2026-05-21T12:00:00Z", {
        voiceRangeLowMidi: 50,
        voiceRangeHighMidi: 74,
        voiceRangeSourceMethod: "MIC_MEASURE",
      }),
      buildEntry("oldest", "2026-05-21T10:00:00Z", {
        voiceRangeLowMidi: 52,
        voiceRangeHighMidi: 70,
        voiceRangeSourceMethod: "SELF_REPORT",
      }),
    ]);

    expect(result).not.toBeNull();
    expect(result!.minLowMidi).toBe(50);
    expect(result!.maxHighMidi).toBe(74);
    expect(result!.earliestSpanSemitones).toBe(18);
    expect(result!.latestSpanSemitones).toBe(24);
    expect(result!.spanDeltaSemitones).toBe(6);
    // sourceMethod 도 전달된다.
    expect(result!.points[0].sourceMethod).toBe("SELF_REPORT");
    expect(result!.points[1].sourceMethod).toBe("MIC_MEASURE");
  });
});
