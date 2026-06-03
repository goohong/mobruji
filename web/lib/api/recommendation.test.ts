/**
 * 추천 API 클라이언트 단위 가드.
 *
 * - createRecommendation: POST 경로 + body 직렬화 + 응답 매핑.
 * - readRecommendation: GET 경로에 id 끼움.
 * - excludeSongIds 전달 (재추천 결정성, spec §9 2026-05-21).
 */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "./client";
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

// issue #422: requestId 는 UUIDv7 문자열. fixture 는 BE 가 발급하는 형식과
// 동일한 길이/하이픈 패턴을 모사한다 (실제 v7 검증은 BE 책임).
const SAMPLE_REQUEST_ID = "01933b1c-7f8a-7c2d-9b3e-0123456789ab";

const sampleResponse: RecommendationResponse = {
  requestId: SAMPLE_REQUEST_ID,
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

    const result = await readRecommendation(SAMPLE_REQUEST_ID);

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toMatch(
      new RegExp(`/api/v1/recommendations/${SAMPLE_REQUEST_ID}$`),
    );
    expect((init as RequestInit).method ?? "GET").toBe("GET");
    expect(result).toEqual(sampleResponse);
    // issue #422: 응답 requestId 가 string(UUID) 형식으로 통과해야 한다.
    expect(typeof result.requestId).toBe("string");
    expect(result.requestId).toBe(SAMPLE_REQUEST_ID);
  });

  it("id 에 URL 특수 문자가 섞여도 encodeURIComponent 로 안전하게 호출한다", async () => {
    // 방어 인코딩 가드 — BE id 형식 변경(예: prefix 추가) 대비.
    fetchMock.mockResolvedValueOnce(jsonResponse(sampleResponse));

    await readRecommendation("foo/bar baz");

    const [url] = fetchMock.mock.calls[0];
    expect(url).toMatch(/\/api\/v1\/recommendations\/foo%2Fbar%20baz$/);
  });
});

/**
 * 경계 가드 (closes #646): ApiError 전파 + 옵셔널 필드 직렬화.
 *
 * 도메인 함수가 apiFetch wrapper의 ApiError를 swallow하지 않는지,
 * `mood`/`excludeSongIds` 직렬화가 BE 계약(nullable enum, 키 생략 시
 * SeedDeriver default seed)과 일치하는지를 가둔다.
 */
describe("recommendation API 경계 가드", () => {
  const base = {
    sessionId: "sess-1",
    voiceRangeLow: 48,
    voiceRangeHigh: 72,
  } as const;

  it("create/read 모두 ApiError를 swallow 하지 않고 그대로 전파한다", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ message: "bad" }, 400));
    await expect(createRecommendation({ ...base })).rejects.toMatchObject({
      name: "ApiError",
      status: 400,
    });
    fetchMock.mockResolvedValueOnce(jsonResponse({ message: "nope" }, 404));
    await expect(
      readRecommendation("does-not-exist-uuid"),
    ).rejects.toBeInstanceOf(ApiError);
  });

  it("excludeSongIds 생략 시 body 에서 키 자체가 빠진다 (BE SeedDeriver default seed 경로)", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(sampleResponse, 201));
    await createRecommendation({ ...base, mood: "CALM" });
    const body = (fetchMock.mock.calls[0][1] as RequestInit).body as string;
    expect(JSON.parse(body)).toEqual({ ...base, mood: "CALM" });
    expect(body).not.toContain("excludeSongIds");
  });

  it("mood: null 명시는 body 에 \"mood\":null 로 직렬화된다 (BE nullable enum 호환)", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(sampleResponse, 201));
    await createRecommendation({ ...base, mood: null });
    const body = (fetchMock.mock.calls[0][1] as RequestInit).body as string;
    expect(body).toContain('"mood":null');
  });

  it("persona=P-E 지정 시 body 에 \"persona\":\"P-E\" 로 직렬화된다 (BE #1598 안전곡 프리셋)", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(sampleResponse, 201));
    await createRecommendation({ ...base, persona: "P-E" });
    const body = (fetchMock.mock.calls[0][1] as RequestInit).body as string;
    expect(JSON.parse(body)).toEqual({ ...base, persona: "P-E" });
  });

  it("persona 생략 시 body 에서 키 자체가 빠진다 (현행 default 가중 하위호환)", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(sampleResponse, 201));
    await createRecommendation({ ...base });
    const body = (fetchMock.mock.calls[0][1] as RequestInit).body as string;
    expect(body).not.toContain("persona");
  });
});
