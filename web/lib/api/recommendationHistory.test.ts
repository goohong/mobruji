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

import { ApiError } from "./client";
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

  // boundary: HTTP 에러/응답 envelope 결손 가드 (이슈 #659).
  // 페이지네이션 인자(page/size/cursor)는 API spec 상 존재하지 않으므로(세션 전체 조회)
  // 인자 boundary 대신 HTTP/envelope 경계만 검증한다.

  it("given BE 가 401 (SessionAuthGuard 헤더 불일치) 을 응답, when called, then ApiError 가 status=401 로 전파된다", async () => {
    // assertion 두 개라 호출 2회 — 매번 401 응답이 일관되게 와야 한다 (#745).
    fetchMock.mockResolvedValue(
      jsonResponse({ message: "session mismatch" }, 401),
    );

    await expect(readRecommendationHistory("sess-abc")).rejects.toMatchObject({
      name: "ApiError",
      status: 401,
    });
    await expect(readRecommendationHistory("sess-abc")).rejects.toBeInstanceOf(
      ApiError,
    );
  });

  it("given BE 가 500 을 응답, when called, then ApiError 가 status=500 로 전파된다", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ message: "boom" }, 500));

    await expect(readRecommendationHistory("sess-abc")).rejects.toMatchObject({
      name: "ApiError",
      status: 500,
    });
  });

  it("given BE envelope 가 비정상(키 결손) 이어도, when called, then 응답을 그대로 통과시킨다(호출 측 책임)", async () => {
    // envelope 결손은 클라이언트가 막지 않고 호출 측 (React Query select) 이 처리.
    fetchMock.mockResolvedValueOnce(jsonResponse({}));

    const result = await readRecommendationHistory("sess-xyz");

    expect(result).toEqual({});
  });
});
