/**
 * 좋아요/북마크 API 클라이언트 단위 테스트 (closes #184 + fix #845).
 *
 * 범위:
 *  - toggleLike: POST body 직렬화 + X-Session-Id 헤더 + 응답 mapping (liked).
 *  - readLikesBySessionId: GET path 인코딩 + X-Session-Id 헤더 + wrapper 응답.
 *  - toggleBookmark: 응답 필드 `bookmarked` 그대로 노출 + 헤더 전달.
 *  - 멱등 토글: 같은 입력 두 번 호출 시 두 번째 응답이 liked=false임을 검증
 *    (실제 BE를 mock으로 흉내).
 *
 * 회귀 가드 (#845):
 *  - 4 함수 모두 `X-Session-Id` 헤더를 전달해야 한다. 헤더 누락 시 BE가 401 → production 영향.
 *  - GET 응답은 `LikeListResponse` / `BookmarkListResponse` wrapper 가 그대로 반환된다.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "./client";
import {
  readBookmarksBySessionId,
  readLikesBySessionId,
  toggleBookmark,
  toggleLike,
  type BookmarkListResponse,
  type LikeListResponse,
} from "./feedback";

const fetchMock = vi.fn();
const originalFetch = globalThis.fetch;

beforeEach(() => {
  fetchMock.mockReset();
  globalThis.fetch = fetchMock as unknown as typeof fetch;
});

afterEach(() => {
  globalThis.fetch = originalFetch;
});

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

/**
 * Headers 인스턴스 또는 record 모두 받아 단일 key 의 값을 꺼낸다.
 * `apiFetch` 가 RequestInit 의 headers 를 Headers 로 그대로 또는 record 로 넘기는 두 경우
 * 모두 대응 — 본 fetch mock 가드는 헤더 전달 회귀만 보면 충분하다.
 */
function readHeader(init: RequestInit, key: string): string | null {
  const headers = init.headers;
  if (!headers) {
    return null;
  }
  if (headers instanceof Headers) {
    return headers.get(key);
  }
  if (Array.isArray(headers)) {
    const found = headers.find(([k]) => k.toLowerCase() === key.toLowerCase());
    return found ? found[1] : null;
  }
  const record = headers as Record<string, string>;
  const match = Object.keys(record).find(
    (k) => k.toLowerCase() === key.toLowerCase(),
  );
  return match ? record[match] : null;
}

function buildLikeWrapper(songIds: number[]): LikeListResponse {
  return {
    responses: songIds.map((songId) => ({
      id: songId * 10,
      song: {
        id: songId,
        title: `곡-${songId}`,
        artist: `가수-${songId}`,
        releaseYear: 2024,
        keyOriginal: "C_MAJOR",
        bpm: 110,
        mood: "UPBEAT",
        language: "ko",
        genre: "POP",
        tjNumber: null,
        kyNumber: null,
        metadataSource: "MANUAL_SEED",
      },
      likedAt: "2026-05-20T12:00:00",
    })),
    page: 0,
    size: 20,
    totalCount: songIds.length,
    hasNext: false,
  };
}

function buildBookmarkWrapper(songIds: number[]): BookmarkListResponse {
  return {
    responses: songIds.map((songId) => ({
      id: songId * 10,
      song: {
        id: songId,
        title: `곡-${songId}`,
        artist: `가수-${songId}`,
        releaseYear: 2024,
        keyOriginal: "C_MAJOR",
        bpm: 110,
        mood: "UPBEAT",
        language: "ko",
        genre: "POP",
        tjNumber: null,
        kyNumber: null,
        metadataSource: "MANUAL_SEED",
      },
      bookmarkedAt: "2026-05-20T12:00:00",
    })),
    page: 0,
    size: 20,
    totalCount: songIds.length,
    hasNext: false,
  };
}

describe("toggleLike", () => {
  it("POST /api/v1/likes 로 sessionId/songId를 직렬화하고 X-Session-Id 헤더를 전달", async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse({ liked: true, songId: 42 }),
    );

    const result = await toggleLike({ sessionId: "sess-abc", songId: 42 });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toMatch(/\/api\/v1\/likes$/);
    expect((init as RequestInit).method).toBe("POST");
    expect((init as RequestInit).body).toBe(
      JSON.stringify({ sessionId: "sess-abc", songId: 42 }),
    );
    // closes #845 — X-Session-Id 헤더 누락은 BE 401.
    expect(readHeader(init as RequestInit, "X-Session-Id")).toBe("sess-abc");
    expect(result).toEqual({ liked: true, songId: 42 });
  });

  // 멱등 — BE는 두 번째 호출에서 active=false를 응답한다 (LikeService.toggle 약속).
  it("같은 (sessionId, songId)로 두 번 호출하면 두 번째 응답 liked=false (멱등 토글)", async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse({ liked: true, songId: 7 }))
      .mockResolvedValueOnce(jsonResponse({ liked: false, songId: 7 }));

    const first = await toggleLike({ sessionId: "sess-1", songId: 7 });
    const second = await toggleLike({ sessionId: "sess-1", songId: 7 });

    expect(first.liked).toBe(true);
    expect(second.liked).toBe(false);
  });
});

describe("readLikesBySessionId", () => {
  it("GET /api/v1/sessions/{id}/likes 로 path/헤더를 인코딩하고 wrapper 응답을 반환", async () => {
    const payload = buildLikeWrapper([10]);
    fetchMock.mockResolvedValueOnce(jsonResponse(payload));

    const result = await readLikesBySessionId("sess 1");

    const [url, init] = fetchMock.mock.calls[0];
    // 공백은 %20으로 인코딩되어야 한다.
    expect(url).toMatch(/\/api\/v1\/sessions\/sess%201\/likes$/);
    expect((init as RequestInit).method).toBe("GET");
    // closes #845 — 헤더는 path 와 동일 원문 (인코딩 X) — voiceRangeHistory.ts 와 동일 패턴.
    expect(readHeader(init as RequestInit, "X-Session-Id")).toBe("sess 1");
    expect(result).toEqual(payload);
    expect(result.responses).toHaveLength(1);
    expect(result.responses[0].song.id).toBe(10);
  });
});

describe("toggleBookmark", () => {
  it("POST /api/v1/bookmarks 호출 + X-Session-Id 헤더 + bookmarked 필드 노출", async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse({ bookmarked: true, songId: 99 }),
    );

    const result = await toggleBookmark({ sessionId: "sess-x", songId: 99 });

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toMatch(/\/api\/v1\/bookmarks$/);
    expect((init as RequestInit).method).toBe("POST");
    expect((init as RequestInit).body).toBe(
      JSON.stringify({ sessionId: "sess-x", songId: 99 }),
    );
    // closes #845 — 헤더 필수.
    expect(readHeader(init as RequestInit, "X-Session-Id")).toBe("sess-x");
    expect(result).toEqual({ bookmarked: true, songId: 99 });
  });

  // 멱등 — toggleLike 와 동일 약속(BookmarkService.toggle).
  it("같은 (sessionId, songId)로 두 번 호출하면 두 번째 응답 bookmarked=false", async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse({ bookmarked: true, songId: 3 }))
      .mockResolvedValueOnce(jsonResponse({ bookmarked: false, songId: 3 }));

    const first = await toggleBookmark({ sessionId: "sess-2", songId: 3 });
    const second = await toggleBookmark({ sessionId: "sess-2", songId: 3 });

    expect(first.bookmarked).toBe(true);
    expect(second.bookmarked).toBe(false);
  });
});

// BE 4xx/5xx → 도메인 함수가 ApiError(status/body 보존)를 그대로 전파해야 한다
// (호출 측 store/UI 가 status로 분기). client.ts 가드를 우회하지 않음을 회귀 보장.
describe("feedback 도메인 함수 오류 전파", () => {
  it("toggleLike: BE 4xx 응답 시 ApiError(status/body 보존) 전파", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ message: "x" }, 404));
    const e = (await toggleLike({ sessionId: "s", songId: 1 }).catch(
      (c: unknown) => c,
    )) as ApiError;
    expect(e).toBeInstanceOf(ApiError);
    expect(e.status).toBe(404);
    expect(e.body).toEqual({ message: "x" });
  });

  it("toggleBookmark: BE 5xx 응답 시 ApiError 전파", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ message: "x" }, 500));
    const e = (await toggleBookmark({ sessionId: "s", songId: 2 }).catch(
      (c: unknown) => c,
    )) as ApiError;
    expect(e).toBeInstanceOf(ApiError);
    expect(e.status).toBe(500);
  });
});

describe("readBookmarksBySessionId", () => {
  // 슬래시·비ASCII path 인코딩 + 빈 wrapper 응답 + X-Session-Id 헤더 전달.
  it("path 인코딩 + 헤더 전달 + 빈 wrapper 응답 그대로 반환", async () => {
    const payload = buildBookmarkWrapper([]);
    fetchMock.mockResolvedValueOnce(jsonResponse(payload));
    const result = await readBookmarksBySessionId("sess/한글");
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toMatch(
      /\/api\/v1\/sessions\/sess%2F%ED%95%9C%EA%B8%80\/bookmarks$/,
    );
    // 헤더는 path 와 달리 인코딩 없이 원문 전달.
    expect(readHeader(init as RequestInit, "X-Session-Id")).toBe("sess/한글");
    expect(result).toEqual(payload);
    expect(result.responses).toHaveLength(0);
  });
});
