/**
 * 곡 검색 API 클라이언트 단위 테스트.
 *
 * - URL 구성: keyword 인코딩, 빈 keyword 시 쿼리 생략.
 * - fetch 응답을 그대로 SongResponse[]로 반환.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { searchSongs } from "./song";

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

describe("searchSongs", () => {
  it("keyword가 주어지면 URL-인코딩된 쿼리스트링으로 GET 한다", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse([]));

    await searchSongs("뉴진스");

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toMatch(/\/api\/v1\/songs\?keyword=%EB%89%B4%EC%A7%84%EC%8A%A4$/);
    expect((init as RequestInit).method).toBe("GET");
  });

  it("keyword가 비어 있으면 쿼리스트링을 생략하고 호출한다", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse([]));

    await searchSongs("");

    const [url] = fetchMock.mock.calls[0];
    expect(url).toMatch(/\/api\/v1\/songs$/);
  });

  it("keyword가 undefined여도 쿼리스트링 없이 호출한다", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse([]));

    await searchSongs(undefined);

    const [url] = fetchMock.mock.calls[0];
    expect(url).toMatch(/\/api\/v1\/songs$/);
  });

  it("응답 JSON을 SongResponse[] 그대로 돌려준다", async () => {
    const songs = [
      {
        id: 1,
        title: "Hello",
        artist: "Adele",
        releaseYear: 2015,
        keyOriginal: "F_MINOR",
        bpm: 79,
        mood: "EMOTIONAL",
        language: "en",
        genre: "POP",
        tjNumber: "12345",
        kyNumber: "54321",
        metadataSource: "MANUAL_SEED",
      },
    ];
    fetchMock.mockResolvedValueOnce(jsonResponse(songs));

    const result = await searchSongs("hello");
    expect(result).toEqual(songs);
  });
});
