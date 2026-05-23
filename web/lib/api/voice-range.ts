/**
 * 음역대 API 클라이언트.
 *
 * BE 컨트롤러 `com.mobruji.voice.VoiceRangeController`와 1:1 매칭:
 *   POST /api/v1/voice-ranges            → createOrReplace (201)
 *   GET  /api/v1/voice-ranges/{sessionId} → read
 *   PUT  /api/v1/voice-ranges/{sessionId} → update
 *
 * 타입은 BE record DTO와 동일한 필드명을 유지한다 (camelCase, MIDI int).
 * 자세한 흐름은 docs/features/voice-range-input.md §5-2.
 *
 * 인증 (#883 / PR #881, ADR-0011 §28):
 *   PR #881 이 `VoiceRangeController` 3 endpoint 에 `SessionAuthGuard` 적용 →
 *   `X-Session-Id` 헤더가 body(POST)/path(GET·PUT) 의 sessionId 와 일치해야 200.
 *   누락/blank/불일치 모두 401. 3 함수 모두 헤더 전달.
 *   (feedback.ts / voiceRangeHistory.ts / recommendationHistory.ts 와 동일 패턴.)
 */

import { apiFetch } from "./client";

export type VoiceRangeSourceMethod =
  | "SELF_REPORT"
  | "OCTAVE_PICK"
  | "MIC_MEASURE";

export type VoiceRangeCreateRequest = {
  sessionId: string;
  lowestNoteMidi: number;
  highestNoteMidi: number;
  sourceMethod: VoiceRangeSourceMethod;
};

export type VoiceRangeUpdateRequest = {
  lowestNoteMidi: number;
  highestNoteMidi: number;
  sourceMethod: VoiceRangeSourceMethod;
};

export type VoiceRangeResponse = {
  id: number;
  sessionId: string;
  lowestNoteMidi: number;
  highestNoteMidi: number;
  sourceMethod: VoiceRangeSourceMethod;
  createdAt: string;
  updatedAt: string;
};

export function createVoiceRange(
  request: VoiceRangeCreateRequest,
  options: { signal?: AbortSignal } = {},
): Promise<VoiceRangeResponse> {
  return apiFetch<VoiceRangeResponse>("/api/v1/voice-ranges", {
    method: "POST",
    body: request,
    signal: options.signal,
    headers: { "X-Session-Id": request.sessionId },
  });
}

export function readVoiceRange(
  sessionId: string,
  options: { signal?: AbortSignal } = {},
): Promise<VoiceRangeResponse> {
  return apiFetch<VoiceRangeResponse>(
    `/api/v1/voice-ranges/${encodeURIComponent(sessionId)}`,
    {
      signal: options.signal,
      headers: { "X-Session-Id": sessionId },
    },
  );
}

export function updateVoiceRange(
  sessionId: string,
  request: VoiceRangeUpdateRequest,
  options: { signal?: AbortSignal } = {},
): Promise<VoiceRangeResponse> {
  return apiFetch<VoiceRangeResponse>(
    `/api/v1/voice-ranges/${encodeURIComponent(sessionId)}`,
    {
      method: "PUT",
      body: request,
      signal: options.signal,
      headers: { "X-Session-Id": sessionId },
    },
  );
}
