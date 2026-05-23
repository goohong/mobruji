/**
 * extractVoiceRangeProgress 단위 테스트 (closes #170).
 */

import { describe, expect, it } from "vitest";

import {
  extractVoiceRangeProgress,
  extractVoiceRangeProgressFromSnapshots,
} from "./voiceRangeProgress";
import type { RecommendationHistoryEntry } from "@/store/history";
import type { VoiceRangeSnapshotResponse } from "@/lib/api/voiceRangeHistory";

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

  it("min/max + 음역폭 변화(spanDelta) + low/high delta 를 계산한다", () => {
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
    // spec voice-range-progress §3 — 첫 측정 대비 low/high delta.
    expect(result!.lowMidiDeltaSemitones).toBe(-2); // 52 → 50.
    expect(result!.highMidiDeltaSemitones).toBe(4); // 70 → 74.
    // sourceMethod 도 전달된다.
    expect(result!.points[0].sourceMethod).toBe("SELF_REPORT");
    expect(result!.points[1].sourceMethod).toBe("MIC_MEASURE");
  });

  // ---- regression guards (#416) ----

  it("[regression #416] valid datapoint 가 1개뿐(나머지 legacy)이면 null — boundary 가드", () => {
    // valid 1 + legacy 2 → 총 datapoint 1개 → null (datapoint >= 2 boundary).
    const result = extractVoiceRangeProgress([
      buildEntry("legacy-2", "2026-05-21T12:00:00Z"),
      buildEntry("valid", "2026-05-21T11:00:00Z", {
        voiceRangeLowMidi: 50,
        voiceRangeHighMidi: 72,
      }),
      buildEntry("legacy-1", "2026-05-21T10:00:00Z"),
    ]);

    expect(result).toBeNull();
  });

  it("[regression #416] 한 phase 만 valid (low number / high undefined) entry 는 제외된다", () => {
    // low 만 있고 high 가 undefined / high 만 있고 low 가 undefined → 둘 다 제외.
    // 결과적으로 valid 는 2개 entry 뿐 → summary 반환되며 partial entry 가
    // points 에 섞이지 않아야 한다.
    const result = extractVoiceRangeProgress([
      buildEntry("only-low", "2026-05-21T14:00:00Z", {
        voiceRangeLowMidi: 48,
        // voiceRangeHighMidi 미지정.
      }),
      buildEntry("valid-recent", "2026-05-21T13:00:00Z", {
        voiceRangeLowMidi: 50,
        voiceRangeHighMidi: 72,
      }),
      buildEntry("only-high", "2026-05-21T12:00:00Z", {
        voiceRangeHighMidi: 80,
        // voiceRangeLowMidi 미지정.
      }),
      buildEntry("valid-old", "2026-05-21T10:00:00Z", {
        voiceRangeLowMidi: 52,
        voiceRangeHighMidi: 70,
      }),
    ]);

    expect(result).not.toBeNull();
    expect(result!.points.map((p) => p.id)).toEqual([
      "valid-old",
      "valid-recent",
    ]);
    // partial entry 의 MIDI 값이 min/max 에 영향을 주지 않아야 한다.
    expect(result!.minLowMidi).toBe(50); // not 48 (only-low entry 제외)
    expect(result!.maxHighMidi).toBe(72); // not 80 (only-high entry 제외)
  });

  it("[regression #416] 모든 측정이 동일 MIDI 면 spanDelta=0 + low/highDelta=0 (평평한 progress)", () => {
    // 3회 모두 동일 측정(60~72). 사용자가 음역 변화 없이 같은 결과만 반복 측정 케이스.
    // UI 차트는 flat line, summary 의 모든 delta 는 0 이어야 한다.
    const result = extractVoiceRangeProgress([
      buildEntry("t3", "2026-05-21T12:00:00Z", {
        voiceRangeLowMidi: 60,
        voiceRangeHighMidi: 72,
      }),
      buildEntry("t2", "2026-05-21T11:00:00Z", {
        voiceRangeLowMidi: 60,
        voiceRangeHighMidi: 72,
      }),
      buildEntry("t1", "2026-05-21T10:00:00Z", {
        voiceRangeLowMidi: 60,
        voiceRangeHighMidi: 72,
      }),
    ]);

    expect(result).not.toBeNull();
    expect(result!.points).toHaveLength(3);
    expect(result!.minLowMidi).toBe(60);
    expect(result!.maxHighMidi).toBe(72);
    expect(result!.earliestSpanSemitones).toBe(12);
    expect(result!.latestSpanSemitones).toBe(12);
    expect(result!.spanDeltaSemitones).toBe(0);
    expect(result!.lowMidiDeltaSemitones).toBe(0);
    expect(result!.highMidiDeltaSemitones).toBe(0);
  });
});

function buildSnapshot(
  overrides: Partial<VoiceRangeSnapshotResponse>,
): VoiceRangeSnapshotResponse {
  return {
    id: 1,
    lowMidi: 50,
    highMidi: 70,
    lowestNoteName: "D3",
    highestNoteName: "A4",
    sourceMethod: "SELF_REPORT",
    measuredAt: "2026-05-21T08:00:00",
    ...overrides,
  };
}

describe("extractVoiceRangeProgressFromSnapshots", () => {
  it("snapshot 이 0~1개면 null 을 반환한다", () => {
    expect(extractVoiceRangeProgressFromSnapshots([])).toBeNull();
    expect(
      extractVoiceRangeProgressFromSnapshots([buildSnapshot({ id: 1 })]),
    ).toBeNull();
  });

  it("BE 응답 순서(measuredAt 오름차순)를 그대로 유지하고 summary 를 계산한다", () => {
    // BE 가 첫 측정(52~70) → 최신(50~74)로 오름차순으로 내려준다고 가정.
    const result = extractVoiceRangeProgressFromSnapshots([
      buildSnapshot({
        id: 11,
        lowMidi: 52,
        highMidi: 70,
        measuredAt: "2026-05-21T08:00:00",
        sourceMethod: "SELF_REPORT",
      }),
      buildSnapshot({
        id: 22,
        lowMidi: 50,
        highMidi: 74,
        measuredAt: "2026-05-21T12:00:00",
        sourceMethod: "MIC_MEASURE",
      }),
    ]);

    expect(result).not.toBeNull();
    expect(result!.points).toHaveLength(2);
    expect(result!.points[0].id).toBe("snapshot-11");
    expect(result!.points[1].id).toBe("snapshot-22");
    expect(result!.points[0].sourceMethod).toBe("SELF_REPORT");
    expect(result!.minLowMidi).toBe(50);
    expect(result!.maxHighMidi).toBe(74);
    expect(result!.spanDeltaSemitones).toBe(6);
    expect(result!.lowMidiDeltaSemitones).toBe(-2);
    expect(result!.highMidiDeltaSemitones).toBe(4);
  });

  it("[regression #416] 입력 순서를 그대로 보존한다 — BE 가 정렬 책임을 진다 (별도 sort 없음)", () => {
    // BE 가 약속을 어겨 역순(최신 → 오래된)으로 보낸 worst case.
    // 본 함수는 별도 정렬하지 않으므로 첫 입력이 earliest 로, 마지막이 latest 로 취급된다.
    // 이 보존 동작은 spec §5-2 (정렬 책임은 BE) 와 일치하며, 향후 정렬 로직을 함부로
    // 추가하지 않도록 가드한다.
    const result = extractVoiceRangeProgressFromSnapshots([
      // 의도적으로 측정 시각 역순(latest 가 첫 번째)으로 넣는다.
      buildSnapshot({
        id: 22,
        lowMidi: 50,
        highMidi: 74,
        measuredAt: "2026-05-21T12:00:00",
        sourceMethod: "MIC_MEASURE",
      }),
      buildSnapshot({
        id: 11,
        lowMidi: 52,
        highMidi: 70,
        measuredAt: "2026-05-21T08:00:00",
        sourceMethod: "SELF_REPORT",
      }),
    ]);

    expect(result).not.toBeNull();
    // 입력 순서 그대로 — 첫 입력(id=22) 이 earliest 자리, 마지막(id=11) 이 latest 자리.
    expect(result!.points[0].id).toBe("snapshot-22");
    expect(result!.points[1].id).toBe("snapshot-11");
    // earliest = 첫 입력(50~74, span 24), latest = 마지막(52~70, span 18).
    expect(result!.earliestSpanSemitones).toBe(24);
    expect(result!.latestSpanSemitones).toBe(18);
    expect(result!.spanDeltaSemitones).toBe(-6); // 24 → 18.
    // min/max 는 정렬과 무관하게 전체 datapoint 기준.
    expect(result!.minLowMidi).toBe(50);
    expect(result!.maxHighMidi).toBe(74);
  });
});
