/**
 * 추천 API 클라이언트 단위 가드.
 *
 * - createRecommendation: POST 경로 + body 직렬화 + 응답 매핑.
 * - readRecommendation: GET 경로에 id 끼움.
 * - excludeSongIds 전달 (재추천 결정성, spec §9 2026-05-21).
 */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  createRecommendation,
  readRecommendation,
  type RecommendationResponse,
} from "./recommendation";

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

const sampleResponse: RecommendationResponse = {
  requestId: 1,
  recommendations: [],
};

describe("createRecommendation", () => {
  it("POST /api/v1/recommendations 로 body 를 JSON 직렬화해 호출하고 응답을 매핑한다", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(sampleResponse, 201));

    const result = await createRecommendation({
      sessionId: "sess-1",
      voiceRangeLow: 48,
      voiceRangeHigh: 72,
      mood: "CALM",
      excludeSongIds: [10, 20],
    });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toMatch(/\/api\/v1\/recommendations$/);
    expect((init as RequestInit).method).toBe("POST");
    expect((init as RequestInit).body).toBe(
      JSON.stringify({
        sessionId: "sess-1",
        voiceRangeLow: 48,
        voiceRangeHigh: 72,
        mood: "CALM",
        excludeSongIds: [10, 20],
      }),
    );
    expect(result).toEqual(sampleResponse);
  });
});

describe("readRecommendation", () => {
  it("GET /api/v1/recommendations/{id} 로 호출하고 응답을 그대로 돌려준다", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(sampleResponse));

    const result = await readRecommendation(42);

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toMatch(/\/api\/v1\/recommendations\/42$/);
    expect((init as RequestInit).method ?? "GET").toBe("GET");
    expect(result).toEqual(sampleResponse);
  });
});
