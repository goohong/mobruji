/**
 * voiceRangeHistory API 클라이언트 단위 테스트.
 *
 * 검증 범위:
 *   - GET /api/v1/sessions/{id}/voice-range-history 경로/메서드/응답 매핑.
 *   - sessionId 의 URL encoding (특수 문자 안전).
 *   - 빈 시계열 응답도 정상 반환.
 *
 * spec: docs/features/voice-range-progress.md §5-2.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "./client";
import { readVoiceRangeHistory } from "./voiceRangeHistory";

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

describe("readVoiceRangeHistory", () => {
  it("given sessionId, when called, then GET /api/v1/sessions/{id}/voice-range-history 를 호출하고 응답을 그대로 반환한다", async () => {
    // given: BE 가 measuredAt 오름차순 시계열 2건을 응답.
    const payload = {
      voiceRangeSnapshotResponses: [
        {
          id: 1,
          lowMidi: 52,
          highMidi: 70,
          lowestNoteName: "E3",
          highestNoteName: "A4",
          sourceMethod: "SELF_REPORT",
          measuredAt: "2026-05-21T08:00:00",
        },
        {
          id: 2,
          lowMidi: 50,
          highMidi: 74,
          lowestNoteName: "D3",
          highestNoteName: "D5",
          sourceMethod: "MIC_MEASURE",
          measuredAt: "2026-05-21T12:00:00",
        },
      ],
    };
    fetchMock.mockResolvedValueOnce(jsonResponse(payload));

    // when:
    const result = await readVoiceRangeHistory("sess-abc");

    // then:
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [calledUrl, calledInit] = fetchMock.mock.calls[0];
    expect(calledUrl).toMatch(
      /\/api\/v1\/sessions\/sess-abc\/voice-range-history$/,
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
      jsonResponse({ voiceRangeSnapshotResponses: [] }),
    );

    await readVoiceRangeHistory("a/b c?d");

    const [calledUrl] = fetchMock.mock.calls[0];
    // "/", " ", "?" 가 모두 인코딩되어야 한다.
    expect(calledUrl).toContain("a%2Fb%20c%3Fd");
  });

  it("given snapshot 0건 응답, when called, then 빈 배열 그대로 반환 (호출 측이 빈 상태 분기)", async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse({ voiceRangeSnapshotResponses: [] }),
    );

    const result = await readVoiceRangeHistory("sess-empty");

    expect(result.voiceRangeSnapshotResponses).toEqual([]);
  });

  it("given path encoding, when called, then X-Session-Id 헤더는 raw sessionId (PR #244 SessionAuthGuard 비교 일치)", async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse({ voiceRangeSnapshotResponses: [] }),
    );
    await readVoiceRangeHistory("a/b c");
    const [calledUrl, calledInit] = fetchMock.mock.calls[0];
    expect(calledUrl).toContain("a%2Fb%20c");
    expect(calledInit.headers).toMatchObject({ "X-Session-Id": "a/b c" });
  });

  it("given BE 401 SessionAuthGuard, when called, then ApiError(401) 를 전파한다", async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse({ message: "session mismatch" }, 401),
    );

    await expect(readVoiceRangeHistory("sess-x")).rejects.toMatchObject({
      name: "ApiError",
      status: 401,
    });
  });

  it("given BE 5xx, when called, then ApiError(500) 를 전파한다", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ message: "boom" }, 500));

    await expect(readVoiceRangeHistory("sess-x")).rejects.toBeInstanceOf(
      ApiError,
    );
  });

  it("given AbortSignal, when called, then fetch init.signal 로 전달된다", async () => {
    const controller = new AbortController();
    fetchMock.mockResolvedValueOnce(
      jsonResponse({ voiceRangeSnapshotResponses: [] }),
    );

    await readVoiceRangeHistory("sess-x", controller.signal);

    expect(fetchMock.mock.calls[0][1]).toMatchObject({
      signal: controller.signal,
    });
  });
});
