/**
 * /voice-range 페이지 테스트용 fixture builder (closes #1115).
 *
 * 목적:
 *   - `web/app/voice-range/page.test.tsx` (PR #985 race 가드 3건 포함) + 본 PR 의
 *     `page.race.test.tsx` 가 동일한 응답 shape 을 반복 정의하지 않도록 단일 빌더.
 *   - `VoiceRangeResponse` 의 필드가 늘어나도 fixture 한 곳만 갱신.
 *
 * 도메인 타입 (변경 금지):
 *   - `VoiceRangeResponse` — `web/lib/api/voice-range.ts` 의 BE 응답 record.
 *   - `VoiceRangeSourceMethod` — `SELF_REPORT | OCTAVE_PICK | MIC_MEASURE`.
 *
 * 디자인 원칙:
 *   - 모든 빌더가 `Partial<VoiceRangeResponse>` overrides 받아 한 줄 분기.
 *   - 기본값은 page.test.tsx 의 기존 happy path 케이스 (id=77 / sessionId="test-session-id" /
 *     low=50 / high=65 / sourceMethod="OCTAVE_PICK") 와 호환되는 sane default.
 *   - 호출 측이 `id` / `sessionId` / `low` / `high` 만 명시하면 나머지는 자동.
 *
 * 도입 사유 (2026-05-26, PR #1111 race-guard 패턴 확장):
 *   - `page.test.tsx` 안 `createDeferred<>()` resolve 호출 시 inline `VoiceRangeResponse`
 *     ~9 LOC 반복 정의가 5회 — 본 fixture 로 1줄 builder 호출로 압축.
 *   - 본 fixture 는 production 코드 의존 (`VoiceRangeResponse` type) 만 import, 테스트
 *     인프라 (vitest / react-query) 의존 없음 → `.ts` (not `.tsx`) 유지.
 */

import type {
  VoiceRangeResponse,
  VoiceRangeSourceMethod,
} from "@/lib/api/voice-range";

/**
 * /voice-range 페이지 fixture 의 기본 sessionId — `page.test.tsx` 의
 * `buildSessionStoreMock` 가 반환하는 `ensureSessionId()` 기본값과 동일.
 *
 * 호출 측이 override 안 하면 자동으로 본 값을 사용해 mutation onSuccess 의
 * `queryClient.setQueryData(["voice-range", sessionId])` cache prime 도 같은
 * sessionId 로 키가 잡힌다.
 */
export const TEST_SESSION_ID = "test-session-id";

/**
 * `VoiceRangeResponse` 빌더.
 *
 * 의미 있는 override:
 *   - `id` (mutation onSuccess 의 setVoiceRangeId 호출 검증)
 *   - `sessionId` (cache prime 키 / cross-contamination 시나리오)
 *   - `lowestNoteMidi` / `highestNoteMidi` (특정 음역 케이스)
 *   - `sourceMethod` (OCTAVE_PICK / SELF_REPORT / MIC_MEASURE 분기)
 *
 * 사용 패턴:
 *   ```ts
 *   import { buildVoiceRangeResponse } from "@/lib/test-fixtures/voice-range";
 *
 *   const response = buildVoiceRangeResponse({ id: 88 });
 *   createVoiceRangeMock.mockResolvedValueOnce(response);
 *   ```
 *
 * 기본값 (호출 측이 override 안 한 필드):
 *   - id: 77 (page.test.tsx 첫 happy path 케이스와 동일)
 *   - sessionId: "test-session-id" (TEST_SESSION_ID)
 *   - lowestNoteMidi: 48 (C3, page.tsx DEFAULT_LOW_MIDI)
 *   - highestNoteMidi: 69 (A4, page.tsx DEFAULT_HIGH_MIDI)
 *   - sourceMethod: "OCTAVE_PICK" (page.tsx DEFAULT_SOURCE)
 *   - createdAt / updatedAt: 2026-05-21 ISO timestamp (page.test.tsx 와 동일 형식)
 */
export function buildVoiceRangeResponse(
  overrides: Partial<VoiceRangeResponse> = {},
): VoiceRangeResponse {
  const sourceMethod: VoiceRangeSourceMethod =
    overrides.sourceMethod ?? "OCTAVE_PICK";
  return {
    id: overrides.id ?? 77,
    sessionId: overrides.sessionId ?? TEST_SESSION_ID,
    lowestNoteMidi: overrides.lowestNoteMidi ?? 48,
    highestNoteMidi: overrides.highestNoteMidi ?? 69,
    sourceMethod,
    createdAt: overrides.createdAt ?? "2026-05-21T00:00:00Z",
    updatedAt: overrides.updatedAt ?? "2026-05-21T00:00:00Z",
  };
}
