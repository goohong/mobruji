/**
 * recommendationHistory API 클라이언트 단위 테스트.
 *
 * 검증 범위:
 *   - GET /api/v1/sessions/{id}/recommendation-history 경로/메서드/응답 매핑.
 *   - sessionId 의 URL encoding (특수 문자 안전).
 *   - X-Session-Id 헤더 동봉 (PR #244 SessionAuthGuard).
 *   - 빈 히스토리 응답도 정상 반환.
 *
 * spec: docs/features/recommendation-history-and-feedback.md §5-2.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { readRecommendationHistory } from "./recommendationHistory";

const fetchMock = vi.fn();

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  fetchMock.mockReset();
  vi.unstubAllGlobals();
});

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

describe("readRecommendationHistory", () => {
  it("given sessionId, when called, then GET /api/v1/sessions/{id}/recommendation-history 를 호출하고 응답을 그대로 반환한다", async () => {
    // given: BE 가 최신순 히스토리 1건을 응답.
    const payload = {
      recommendationHistoryResponses: [
        {
          requestId: 42,
          sessionId: "sess-abc",
          voiceRangeLow: 52,
          voiceRangeHigh: 70,
          mood: "UPBEAT",
          preferredBpm: 120,
          requestedAt: "2026-05-21T08:00:00",
          recommendations: [
            {
              song: {
                id: 1,
                title: "테스트 곡",
                artist: "테스트 아티스트",
                releaseYear: 2024,
                keyOriginal: "C_MAJOR",
                bpm: 120,
                mood: "UPBEAT",
                language: "ko",
                genre: "POP",
                tjNumber: "12345",
                kyNumber: "67890",
                metadataSource: "MANUAL_SEED",
              },
              score: 0.9,
              matchReason: "음역대 일치",
              rankPosition: 1,
            },
          ],
        },
      ],
    };
    fetchMock.mockResolvedValueOnce(jsonResponse(payload));

    // when:
    const result = await readRecommendationHistory("sess-abc");

    // then:
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [calledUrl, calledInit] = fetchMock.mock.calls[0];
    expect(calledUrl).toMatch(
      /\/api\/v1\/sessions\/sess-abc\/recommendation-history$/,
    );
    expect(calledInit).toMatchObject({
      method: "GET",
      // PR #244 SessionAuthGuard — path sessionId 와 동일한 X-Session-Id 헤더.
      headers: expect.objectContaining({ "X-Session-Id": "sess-abc" }),
    });
    expect(result).toEqual(payload);
  });

  it("given sessionId 에 URL 특수 문자 포함, when called, then encodeURIComponent 가 적용된다", async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse({ recommendationHistoryResponses: [] }),
    );

    await readRecommendationHistory("sess/with space");

    const [calledUrl] = fetchMock.mock.calls[0];
    expect(calledUrl).toMatch(
      /\/api\/v1\/sessions\/sess%2Fwith%20space\/recommendation-history$/,
    );
  });

  it("given BE 가 빈 히스토리를 응답, when called, then 빈 배열을 그대로 반환한다", async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse({ recommendationHistoryResponses: [] }),
    );

    const result = await readRecommendationHistory("sess-xyz");

    expect(result.recommendationHistoryResponses).toEqual([]);
  });

  it("given AbortSignal 이 전달되면, when called, then fetch init.signal 로 전파한다", async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse({ recommendationHistoryResponses: [] }),
    );
    const controller = new AbortController();

    await readRecommendationHistory("sess-xyz", controller.signal);

    const [, calledInit] = fetchMock.mock.calls[0];
    expect(calledInit?.signal).toBe(controller.signal);
  });
});
