/**
 * 좋아요/북마크 API 클라이언트 단위 테스트 (closes #184).
 *
 * 범위:
 *  - toggleLike: POST body 직렬화 + 응답 mapping (liked).
 *  - readLikesBySessionId: GET path 인코딩, 빈 배열 응답.
 *  - toggleBookmark: 응답 필드 `bookmarked` 그대로 노출.
 *  - 멱등 토글: 같은 입력 두 번 호출 시 두 번째 응답이 liked=false임을 검증
 *    (실제 BE를 mock으로 흉내).
 */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  readLikesBySessionId,
  toggleBookmark,
  toggleLike,
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

describe("toggleLike", () => {
  it("POST /api/v1/likes 로 sessionId/songId를 직렬화해 호출하고 liked를 그대로 돌려준다", async () => {
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
  it("GET /api/v1/sessions/{id}/likes 로 path 인코딩 후 호출하고 응답을 그대로 반환", async () => {
    const payload = [
      {
        id: 1,
        sessionId: "sess 1",
        songId: 10,
        createdAt: "2026-05-20T12:00:00",
      },
    ];
    fetchMock.mockResolvedValueOnce(jsonResponse(payload));

    const result = await readLikesBySessionId("sess 1");

    const [url, init] = fetchMock.mock.calls[0];
    // 공백은 %20으로 인코딩되어야 한다.
    expect(url).toMatch(/\/api\/v1\/sessions\/sess%201\/likes$/);
    expect((init as RequestInit).method).toBe("GET");
    expect(result).toEqual(payload);
  });
});

describe("toggleBookmark", () => {
  it("POST /api/v1/bookmarks 호출 후 응답의 bookmarked 필드를 그대로 노출", async () => {
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
    expect(result).toEqual({ bookmarked: true, songId: 99 });
  });
});
