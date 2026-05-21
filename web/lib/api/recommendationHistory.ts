/**
 * 추천 히스토리(history) API 클라이언트.
 *
 * BE 엔드포인트 (spec: docs/features/recommendation-history-and-feedback.md §5-2, PR C #237):
 *   GET /api/v1/sessions/{sessionId}/recommendation-history
 *     → RecommendationHistoryListResponse { recommendationHistoryResponses: [...] }
 *
 * BE record DTO (`com.mobruji.recommendation.api.dto.RecommendationHistoryResponse`)와
 * 1:1 필드명 매칭(camelCase). breakdown 은 영속하지 않으므로 응답에서 제외된다 —
 * `recommendation.ts` 의 `RecommendedSongResponse` 와 동일 shape 이지만 BE 가
 * `breakdown` 필드를 null 로 내려준다(스펙 §5-2 주석 참조). fe 는 미사용 필드로 둔다.
 *
 * 정책:
 *   - 응답이 비어 있는 경우(`recommendationHistoryResponses: []`)는 정상 동작이며,
 *     호출 측에서 빈 상태 UI 분기 책임을 진다.
 *   - sessionId 가 falsy 인 경우 호출 측에서 호출 자체를 막는다(useQuery `enabled`).
 *   - 본 모듈은 fetch/직렬화만 담당하고 React Query/상태/캐시는 호출 측이 다룬다.
 *
 * 인증 (spec §5-2-1, ADR-0011):
 *   PR #244 (SessionAuthGuard) — `X-Session-Id` 헤더와 path sessionId 가 일치해야
 *   200 을 돌려준다(불일치/누락 → 401). 헤더는 path 와 동일한 값으로 보낸다.
 */

import { apiFetch } from "./client";
import type {
  Mood,
  RecommendedSongResponse,
} from "./recommendation";

/**
 * 추천 히스토리 1건 — 요청 메타 + 결과 곡 리스트.
 *
 * BE record `RecommendationHistoryResponse` 와 1:1 매칭. `requestedAt` 은
 * ISO8601 LocalDateTime (예: "2026-05-21T08:00:00") — 타임존 미포함.
 */
export type RecommendationHistoryEntryResponse = {
  requestId: number;
  sessionId: string;
  voiceRangeLow: number;
  voiceRangeHigh: number;
  /** SCREAMING_SNAKE 문자열 또는 null (사용자가 mood 를 안 골랐을 수 있음). */
  mood: Mood | null;
  preferredBpm: number | null;
  /** ISO8601 LocalDateTime. createdAt = 추천 요청 시각. */
  requestedAt: string;
  recommendations: RecommendedSongResponse[];
};

export type RecommendationHistoryListResponse = {
  recommendationHistoryResponses: RecommendationHistoryEntryResponse[];
};

/**
 * 세션의 추천 히스토리를 최신순(createdAt DESC)으로 조회한다.
 *
 * PR #244 (SessionAuthGuard) — `X-Session-Id` 헤더가 path sessionId 와 일치해야
 * 200. 불일치/누락 → 401. 헤더는 path 와 동일한 값으로 보낸다 (voice-range-history
 * 와 동일 패턴).
 *
 * @param sessionId 익명 세션 ID (useSessionStore.ensureSessionId() 로 확보).
 * @param signal AbortSignal (React Query 가 자동 전달 — useQuery queryFn 인자).
 */
export function readRecommendationHistory(
  sessionId: string,
  signal?: AbortSignal,
): Promise<RecommendationHistoryListResponse> {
  return apiFetch<RecommendationHistoryListResponse>(
    `/api/v1/sessions/${encodeURIComponent(sessionId)}/recommendation-history`,
    {
      signal,
      headers: { "X-Session-Id": sessionId },
    },
  );
}
