/**
 * 음역 발전 추적 helper (closes #170).
 *
 * 배경:
 *   - Yousician 같은 서비스가 학습 진행을 "주별 그래프"로 보여주는 영감을 받아
 *     mobruji 도 사용자가 음역을 여러 번 측정·갱신할수록 측정값이 점점 안정되거나
 *     음역폭이 넓어지는 흐름을 시각화한다.
 *   - 히스토리(`useHistoryStore`) entries 에는 추천 시점의 음역 스냅샷이 보관되어
 *     있으므로 BE 추가 호출 없이 클라이언트 측에서 시계열을 구성한다.
 *
 * 정책:
 *   - voiceRangeLowMidi / voiceRangeHighMidi 가 둘 다 존재하는 entry 만 datapoint 로 인정.
 *   - 같은 `voiceRangeId` 의 연속 entry 는 측정값이 동일할 가능성이 높으므로,
 *     "보이는 차트"를 위해 중복 제거하지 않고 모두 표시한다 (선이 평평한 구간 = 안정).
 *   - 입력 entries 는 최신 → 오래된 정렬(useHistoryStore prepend 정책)이라 가정한다.
 *     반환 datapoints 는 **시간 오름차순**(과거 → 최근)으로 정규화 — 차트 x축이
 *     자연스럽게 왼쪽=과거 오른쪽=현재가 되도록.
 */

import type { RecommendationHistoryEntry } from "@/store/history";
import type { VoiceRangeSnapshotResponse } from "@/lib/api/voiceRangeHistory";

export type VoiceRangeProgressPoint = {
  /** entry.id — React key 로 그대로 사용. */
  id: string;
  /** entry.requestedAt (ISO8601). */
  requestedAt: string;
  /** 측정 시점의 가장 낮은 음 MIDI. */
  lowMidi: number;
  /** 측정 시점의 가장 높은 음 MIDI. */
  highMidi: number;
  /** voiceRangeId — 같은 측정 그룹 식별 (UI에서 그룹화 시 사용 가능). */
  voiceRangeId: number | null;
  /** 자동/수동 등 측정 출처. */
  sourceMethod?: "SELF_REPORT" | "OCTAVE_PICK" | "MIC_MEASURE";
};

export type VoiceRangeProgressSummary = {
  points: VoiceRangeProgressPoint[];
  /** 전체 datapoint 의 lowMidi 최솟값 — y축 하단 계산용. */
  minLowMidi: number;
  /** 전체 datapoint 의 highMidi 최댓값 — y축 상단 계산용. */
  maxHighMidi: number;
  /** 최신 측정의 음역폭 (highMidi - lowMidi). */
  latestSpanSemitones: number;
  /** 가장 오래된 측정의 음역폭. */
  earliestSpanSemitones: number;
  /** 음역폭의 변화 (latest - earliest). 양수면 넓어진 것. */
  spanDeltaSemitones: number;
  /**
   * 첫 측정 대비 최신 측정의 lowMidi delta(반음 단위).
   * 양수면 최저음이 높아진 것(=음역 하단이 좁아짐). spec §3.
   */
  lowMidiDeltaSemitones: number;
  /**
   * 첫 측정 대비 최신 측정의 highMidi delta(반음 단위).
   * 양수면 최고음이 더 높아진 것(=음역 상단이 넓어짐). spec §3.
   */
  highMidiDeltaSemitones: number;
};

/**
 * 히스토리 entries 에서 음역 시계열을 추출한다.
 *
 * @param entries 히스토리 store entries (최신 → 오래된 순서).
 * @returns datapoint 가 2개 이상이면 summary, 아니면 null (호출 측이 빈 상태 UI로 분기).
 */
export function extractVoiceRangeProgress(
  entries: readonly RecommendationHistoryEntry[],
): VoiceRangeProgressSummary | null {
  const points: VoiceRangeProgressPoint[] = [];
  for (const entry of entries) {
    if (
      typeof entry.voiceRangeLowMidi !== "number" ||
      typeof entry.voiceRangeHighMidi !== "number"
    ) {
      continue;
    }
    points.push({
      id: entry.id,
      requestedAt: entry.requestedAt,
      lowMidi: entry.voiceRangeLowMidi,
      highMidi: entry.voiceRangeHighMidi,
      voiceRangeId: entry.voiceRangeId,
      sourceMethod: entry.voiceRangeSourceMethod,
    });
  }

  if (points.length < 2) {
    return null;
  }

  // entries 는 최신 → 오래된 순이므로 reverse 해서 오름차순(과거 → 현재)으로.
  points.reverse();

  let minLowMidi = points[0].lowMidi;
  let maxHighMidi = points[0].highMidi;
  for (const point of points) {
    if (point.lowMidi < minLowMidi) {
      minLowMidi = point.lowMidi;
    }
    if (point.highMidi > maxHighMidi) {
      maxHighMidi = point.highMidi;
    }
  }

  const earliest = points[0];
  const latest = points[points.length - 1];
  const earliestSpan = earliest.highMidi - earliest.lowMidi;
  const latestSpan = latest.highMidi - latest.lowMidi;

  return {
    points,
    minLowMidi,
    maxHighMidi,
    latestSpanSemitones: latestSpan,
    earliestSpanSemitones: earliestSpan,
    spanDeltaSemitones: latestSpan - earliestSpan,
    lowMidiDeltaSemitones: latest.lowMidi - earliest.lowMidi,
    highMidiDeltaSemitones: latest.highMidi - earliest.highMidi,
  };
}

/**
 * BE `/api/v1/sessions/{id}/voice-range-history` 응답에서 시계열 summary 를 추출한다.
 *
 * spec: docs/features/voice-range-progress.md §3 — `/history` 페이지가 backend 데이터를
 * source-of-truth 로 사용. 본 함수는 BE 응답(`voiceRangeSnapshotResponses`, measuredAt
 * 오름차순)을 `VoiceRangeProgressSummary` 로 변환해 기존 `VoiceRangeProgressCard` 가
 * 그대로 받아 렌더할 수 있게 한다.
 *
 * 정책:
 *   - BE 응답은 이미 measuredAt 오름차순이라고 가정한다(spec §5-2). 만약 다르더라도
 *     입력 순서를 보존하고 별도 정렬하지 않는다 — 정렬 책임은 BE가 진다.
 *   - snapshot 이 2개 미만이면 null (호출 측이 빈 상태/CTA 분기).
 *   - `voiceRangeId` 는 BE 스냅샷에 없으므로 null 로 둔다 — UI는 voiceRangeId 를 그룹화
 *     키로만 쓰는데, BE 시계열에서는 그룹화 의미가 사라진다(snapshot id 가 unique).
 *
 * @param snapshots BE 시계열 응답 (measuredAt 오름차순).
 * @returns snapshot ≥ 2 이면 summary, 아니면 null.
 */
export function extractVoiceRangeProgressFromSnapshots(
  snapshots: readonly VoiceRangeSnapshotResponse[],
): VoiceRangeProgressSummary | null {
  if (snapshots.length < 2) {
    return null;
  }

  const points: VoiceRangeProgressPoint[] = snapshots.map((snapshot) => ({
    id: `snapshot-${snapshot.id}`,
    requestedAt: snapshot.measuredAt,
    lowMidi: snapshot.lowMidi,
    highMidi: snapshot.highMidi,
    voiceRangeId: null,
    sourceMethod: snapshot.sourceMethod,
  }));

  let minLowMidi = points[0].lowMidi;
  let maxHighMidi = points[0].highMidi;
  for (const point of points) {
    if (point.lowMidi < minLowMidi) {
      minLowMidi = point.lowMidi;
    }
    if (point.highMidi > maxHighMidi) {
      maxHighMidi = point.highMidi;
    }
  }

  const earliest = points[0];
  const latest = points[points.length - 1];
  const earliestSpan = earliest.highMidi - earliest.lowMidi;
  const latestSpan = latest.highMidi - latest.lowMidi;

  return {
    points,
    minLowMidi,
    maxHighMidi,
    latestSpanSemitones: latestSpan,
    earliestSpanSemitones: earliestSpan,
    spanDeltaSemitones: latestSpan - earliestSpan,
    lowMidiDeltaSemitones: latest.lowMidi - earliest.lowMidi,
    highMidiDeltaSemitones: latest.highMidi - earliest.highMidi,
  };
}
