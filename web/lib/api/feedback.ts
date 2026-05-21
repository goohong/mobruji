/**
 * 좋아요/북마크 API 클라이언트 (closes #184, spec PR D 일부).
 *
 * BE 컨트롤러 `com.mobruji.feedback.api.LikeController`, `BookmarkController`와 1:1:
 *   POST /api/v1/likes                          → toggle (liked: bool)
 *   GET  /api/v1/sessions/{sessionId}/likes     → list (LikeResponse[])
 *   POST /api/v1/bookmarks                      → toggle (bookmarked: bool)
 *   GET  /api/v1/sessions/{sessionId}/bookmarks → list (BookmarkResponse[])
 *
 * 멱등 토글:
 *   - 같은 (sessionId, songId)로 두 번째 호출하면 active=false 응답 (=취소됨).
 *   - 호출 측은 응답의 `liked`/`bookmarked`를 그대로 store에 반영하면 BE와 동기화된다.
 *
 * 보안 (PR #129 / safeLog 정책):
 *   - 요청 body의 `sessionId`는 PII 마스킹 대상. 호출 측에서 직접 log 찍지 말고
 *     실패 시 `safeLog.error`로 위임할 것.
 *   - 클라이언트는 sessionId를 `useSessionStore.ensureSessionId()`로 얻어 전달한다.
 */

import { apiFetch } from "./client";

export type LikeToggleResponse = {
  liked: boolean;
  songId: number;
};

export type LikeResponse = {
  id: number;
  sessionId: string;
  songId: number;
  createdAt: string;
};

export type BookmarkToggleResponse = {
  bookmarked: boolean;
  songId: number;
};

export type BookmarkResponse = {
  id: number;
  sessionId: string;
  songId: number;
  createdAt: string;
};

type ToggleRequest = {
  sessionId: string;
  songId: number;
};

/**
 * 좋아요 토글. 같은 (sessionId, songId)로 두 번 호출하면 자동 취소.
 * 응답 `liked`가 새 상태 (true=좋아요됨, false=취소됨).
 */
export function toggleLike(
  request: ToggleRequest,
  signal?: AbortSignal,
): Promise<LikeToggleResponse> {
  return apiFetch<LikeToggleResponse>("/api/v1/likes", {
    method: "POST",
    body: request,
    signal,
  });
}

/**
 * 세션의 좋아요 목록. 최신순은 BE가 보장 — 현재 정렬 약속 없음.
 * 빈 세션이면 빈 배열 반환.
 */
export function readLikesBySessionId(
  sessionId: string,
  signal?: AbortSignal,
): Promise<LikeResponse[]> {
  return apiFetch<LikeResponse[]>(
    `/api/v1/sessions/${encodeURIComponent(sessionId)}/likes`,
    { signal },
  );
}

/**
 * 북마크 토글. 동작 의미는 `toggleLike`와 동일 — 응답 필드만 `bookmarked`.
 */
export function toggleBookmark(
  request: ToggleRequest,
  signal?: AbortSignal,
): Promise<BookmarkToggleResponse> {
  return apiFetch<BookmarkToggleResponse>("/api/v1/bookmarks", {
    method: "POST",
    body: request,
    signal,
  });
}

/**
 * 세션의 북마크 목록.
 */
export function readBookmarksBySessionId(
  sessionId: string,
  signal?: AbortSignal,
): Promise<BookmarkResponse[]> {
  return apiFetch<BookmarkResponse[]>(
    `/api/v1/sessions/${encodeURIComponent(sessionId)}/bookmarks`,
    { signal },
  );
}
