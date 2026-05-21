/**
 * 곡 검색/단건 API 클라이언트 단위 테스트.
 *
 * - URL 구성: keyword 인코딩, 빈 keyword 시 쿼리 생략.
 * - fetch 응답을 그대로 SongResponse[]로 반환.
 * - 단건 조회(readSongById): id를 path로 전달, 404 시 ApiError를 던진다.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "./client";
import { readSongById, searchSongs } from "./song";

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

describe("readSongById", () => {
  it("id를 path에 끼워 GET 호출하고 SongResponse를 그대로 돌려준다", async () => {
    const song = {
      id: 42,
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
    };
    fetchMock.mockResolvedValueOnce(jsonResponse(song));

    const result = await readSongById(42);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toMatch(/\/api\/v1\/songs\/42$/);
    expect((init as RequestInit).method).toBe("GET");
    expect(result).toEqual(song);
  });

  it("미존재 id에 대해 404가 오면 ApiError(status=404)를 던진다", async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({ message: "song not found" }), {
        status: 404,
        headers: { "Content-Type": "application/json" },
      }),
    );

    let caught: unknown = null;
    try {
      await readSongById(9999);
    } catch (error) {
      caught = error;
    }
    expect(caught).toBeInstanceOf(ApiError);
    expect((caught as ApiError).status).toBe(404);
  });
});
