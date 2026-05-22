/**
 * 음역 측정 시계열(history) API 클라이언트.
 *
 * BE 엔드포인트 (spec: docs/features/voice-range-progress.md §5-2, PR C):
 *   GET /api/v1/sessions/{sessionId}/voice-range-history
 *     → VoiceRangeHistoryResponse { voiceRangeSnapshotResponses: [...] }
 *
 * BE record DTO (`com.mobruji.voice.api.dto.VoiceRangeSnapshotResponse`)와 1:1
 * 필드명 매칭(camelCase). MIDI 원본 정수와 함께 BE 변환된 음표명(lowestNoteName/
 * highestNoteName)도 그대로 받아 둔다 — fe에서 자체 midiToNoteName도 가능하지만
 * BE 응답을 source-of-truth 로 신뢰한다(spec §3 fe 표시).
 *
 * 정책:
 *   - 응답이 비어 있는 경우(`voiceRangeSnapshotResponses: []`)는 정상 동작이며,
 *     호출 측에서 빈 상태 UI 분기 책임을 진다.
 *   - sessionId 가 falsy 인 경우 호출 측에서 호출 자체를 막는다(useQuery `enabled`).
 *   - 본 모듈은 fetch/직렬화만 담당하고 React Query/상태/캐시는 호출 측이 다룬다.
 */

import { apiFetch } from "./client";
import type { VoiceRangeSourceMethod } from "./voice-range";

export type VoiceRangeSnapshotResponse = {
  id: number;
  lowMidi: number;
  highMidi: number;
  /** BE에서 MIDI → 음표명 변환(예: "C4"). spec §5-1 NoteName 컨벤션. */
  lowestNoteName: string;
  highestNoteName: string;
  sourceMethod: VoiceRangeSourceMethod;
  /** ISO8601 LocalDateTime (예: "2026-05-21T08:00:00"). */
  measuredAt: string;
};

export type VoiceRangeHistoryResponse = {
  voiceRangeSnapshotResponses: VoiceRangeSnapshotResponse[];
};

/**
 * 세션의 음역 측정 시계열을 measuredAt 오름차순으로 조회한다.
 *
 * PR #244 (SessionAuthGuard) 이후 이 endpoint 는 `X-Session-Id` 헤더와 path
 * sessionId 가 일치해야 200 을 돌려준다(불일치/누락 → 401). 헤더는 path 와 동일한
 * 값으로 보낸다 — 어차피 sessionId 원문은 BE 가 path 로도 받으므로 상수시간 비교용
 * 헤더가 추가 보호를 제공하지 않지만, 향후 쿠키 기반으로 옮길 때 호출 코드 변경을
 * 최소화하기 위한 정식 입구가 헤더다.
 *
 * @param sessionId 익명 세션 ID (useSessionStore.ensureSessionId() 로 확보).
 * @param signal AbortSignal (React Query 가 자동 전달 — useQuery queryFn 인자).
 */
export function readVoiceRangeHistory(
  sessionId: string,
  signal?: AbortSignal,
): Promise<VoiceRangeHistoryResponse> {
  return apiFetch<VoiceRangeHistoryResponse>(
    `/api/v1/sessions/${encodeURIComponent(sessionId)}/voice-range-history`,
    {
      signal,
      headers: { "X-Session-Id": sessionId },
    },
  );
}
