/**
 * 좋아요/북마크 API 클라이언트 (closes #184, spec PR D 일부 + 401 fix #845).
 *
 * BE 컨트롤러 `com.mobruji.feedback.api.LikeController`, `BookmarkController`와 1:1:
 *   POST /api/v1/likes                          → toggle (liked: bool)
 *   GET  /api/v1/sessions/{sessionId}/likes     → list (LikeListResponse wrapper)
 *   POST /api/v1/bookmarks                      → toggle (bookmarked: bool)
 *   GET  /api/v1/sessions/{sessionId}/bookmarks → list (BookmarkListResponse wrapper)
 *
 * 멱등 토글:
 *   - 같은 (sessionId, songId)로 두 번째 호출하면 active=false 응답 (=취소됨).
 *   - 호출 측은 응답의 `liked`/`bookmarked`를 그대로 store에 반영하면 BE와 동기화된다.
 *
 * 보안 (PR #129 / safeLog 정책):
 *   - 요청 body의 `sessionId`는 PII 마스킹 대상. 호출 측에서 직접 log 찍지 말고
 *     실패 시 `safeLog.error`로 위임할 것.
 *   - 클라이언트는 sessionId를 `useSessionStore.ensureSessionId()`로 얻어 전달한다.
 *
 * 인증 (#845 fix, spec §5-2-1 / ADR-0011):
 *   PR #244 (SessionAuthGuard) — POST는 body의 sessionId, GET은 path sessionId 가
 *   `X-Session-Id` 헤더와 일치해야 200. 누락/blank/불일치 모두 401. 4 함수 모두 헤더 전달.
 *   (voiceRangeHistory.ts / recommendationHistory.ts 와 동일 패턴.)
 *
 * 응답 wrapper (#845 fix, spec §5-2):
 *   GET 응답은 BE `LikeListResponse` / `BookmarkListResponse` wrapper —
 *   `{ responses: LikeWithSongResponse[], page, size, totalCount, hasNext }`.
 *   `LikeWithSongResponse` / `BookmarkWithSongResponse` 는 곡 메타(`song`)를 join 해서
 *   내려주므로 호출 측은 `readSongById` 로 N+1 fan-out 할 필요가 없다.
 */

import { apiFetch } from "./client";
import type { SongResponse } from "./recommendation";

export type LikeToggleResponse = {
  liked: boolean;
  songId: number;
};

/**
 * 좋아요 1건 + 곡 메타데이터 (join). BE `LikeWithSongResponse` record 와 1:1.
 *
 * spec `recommendation-history-and-feedback.md §5-2` — `/likes` 페이지가 곡 카드를
 * 즉시 렌더할 수 있도록 곡 메타(title/artist/difficulty/lowMidi/highMidi 등)를 동봉.
 * fe 측 N+1 호출 없음.
 */
export type LikeWithSongResponse = {
  /** Like 엔티티 PK. */
  id: number;
  song: SongResponse;
  /** ISO8601 LocalDateTime (예: "2026-05-20T12:00:00"). */
  likedAt: string;
};

/**
 * GET /api/v1/sessions/{id}/likes 응답 wrapper. BE `LikeListResponse` record 와 1:1.
 *
 * 리스트 변수명은 `responses` (BE 코드 컨벤션 CLAUDE.md §4 와 정렬). Spring Data
 * `Page<>` 직접 노출 대신 명시적 wrapper 로 직렬화 안정성 확보.
 */
export type LikeListResponse = {
  responses: LikeWithSongResponse[];
  page: number;
  size: number;
  totalCount: number;
  hasNext: boolean;
};

export type BookmarkToggleResponse = {
  bookmarked: boolean;
  songId: number;
};

/**
 * 북마크 1건 + 곡 메타데이터 (join). BE `BookmarkWithSongResponse` record 와 1:1.
 * {@link LikeWithSongResponse} 와 동일 패턴.
 */
export type BookmarkWithSongResponse = {
  id: number;
  song: SongResponse;
  /** ISO8601 LocalDateTime. */
  bookmarkedAt: string;
};

/**
 * GET /api/v1/sessions/{id}/bookmarks 응답 wrapper. BE `BookmarkListResponse` record 와 1:1.
 */
export type BookmarkListResponse = {
  responses: BookmarkWithSongResponse[];
  page: number;
  size: number;
  totalCount: number;
  hasNext: boolean;
};

type ToggleRequest = {
  sessionId: string;
  songId: number;
};

/**
 * 좋아요 토글. 같은 (sessionId, songId)로 두 번 호출하면 자동 취소.
 * 응답 `liked`가 새 상태 (true=좋아요됨, false=취소됨).
 *
 * 인증: body 의 sessionId 와 `X-Session-Id` 헤더가 일치해야 200 (불일치/누락 → 401).
 * voiceRangeHistory.ts:59 / recommendationHistory.ts:78 와 동일 패턴.
 */
export function toggleLike(
  request: ToggleRequest,
  signal?: AbortSignal,
): Promise<LikeToggleResponse> {
  return apiFetch<LikeToggleResponse>("/api/v1/likes", {
    method: "POST",
    body: request,
    signal,
    headers: { "X-Session-Id": request.sessionId },
  });
}

/**
 * 세션의 좋아요 목록 (곡 메타 join + 페이지네이션).
 *
 * 응답: {@link LikeListResponse} wrapper. `responses` 각 항목은 곡 메타까지 동봉된
 * {@link LikeWithSongResponse} — 호출 측은 `readSongById` fan-out 불필요.
 *
 * 인증: path sessionId 와 `X-Session-Id` 헤더가 일치해야 200 (불일치/누락 → 401).
 * 빈 세션이면 `{ responses: [], page: 0, size: 20, totalCount: 0, hasNext: false }`.
 */
export function readLikesBySessionId(
  sessionId: string,
  signal?: AbortSignal,
): Promise<LikeListResponse> {
  return apiFetch<LikeListResponse>(
    `/api/v1/sessions/${encodeURIComponent(sessionId)}/likes`,
    {
      signal,
      headers: { "X-Session-Id": sessionId },
    },
  );
}

/**
 * 북마크 토글. 동작 의미는 `toggleLike`와 동일 — 응답 필드만 `bookmarked`.
 * 인증 처리도 `toggleLike` 와 동일 (헤더 일치 → 200, 아니면 401).
 */
export function toggleBookmark(
  request: ToggleRequest,
  signal?: AbortSignal,
): Promise<BookmarkToggleResponse> {
  return apiFetch<BookmarkToggleResponse>("/api/v1/bookmarks", {
    method: "POST",
    body: request,
    signal,
    headers: { "X-Session-Id": request.sessionId },
  });
}

/**
 * 세션의 북마크 목록 (곡 메타 join + 페이지네이션).
 * {@link readLikesBySessionId} 와 동일 패턴.
 */
export function readBookmarksBySessionId(
  sessionId: string,
  signal?: AbortSignal,
): Promise<BookmarkListResponse> {
  return apiFetch<BookmarkListResponse>(
    `/api/v1/sessions/${encodeURIComponent(sessionId)}/bookmarks`,
    {
      signal,
      headers: { "X-Session-Id": sessionId },
    },
  );
}
