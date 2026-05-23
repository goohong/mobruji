/**
 * voice-range API 클라이언트 단위 가드 (PR #600 짝).
 *
 * - create: POST body 직렬화 + 201 응답 매핑.
 * - read:   GET path encoding.
 * - update: PUT body 직렬화.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  createVoiceRange,
  readVoiceRange,
  updateVoiceRange,
  type VoiceRangeResponse,
} from "./voice-range";

const fetchMock = vi.fn();
const originalFetch = globalThis.fetch;

beforeEach(() => {
  fetchMock.mockReset();
  globalThis.fetch = fetchMock as unknown as typeof fetch;
});
afterEach(() => {
  globalThis.fetch = originalFetch;
});

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });

const sample: VoiceRangeResponse = {
  id: 7,
  sessionId: "sess-1",
  lowestNoteMidi: 48,
  highestNoteMidi: 72,
  sourceMethod: "SELF_REPORT",
  createdAt: "2026-05-23T00:00:00",
  updatedAt: "2026-05-23T00:00:00",
};

describe("voice-range API", () => {
  it("createVoiceRange: POST /api/v1/voice-ranges + JSON body + 응답 매핑", async () => {
    fetchMock.mockResolvedValueOnce(json(sample, 201));
    const req = {
      sessionId: "sess-1",
      lowestNoteMidi: 48,
      highestNoteMidi: 72,
      sourceMethod: "SELF_REPORT" as const,
    };
    const result = await createVoiceRange(req);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toMatch(/\/api\/v1\/voice-ranges$/);
    expect((init as RequestInit).method).toBe("POST");
    expect((init as RequestInit).body).toBe(JSON.stringify(req));
    expect(result).toEqual(sample);
  });

  it("readVoiceRange: GET + sessionId 특수문자 encodeURIComponent", async () => {
    fetchMock.mockResolvedValueOnce(json(sample));
    await readVoiceRange("a/b c?d");
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toContain("/api/v1/voice-ranges/a%2Fb%20c%3Fd");
    expect((init as RequestInit).method ?? "GET").toBe("GET");
  });

  it("updateVoiceRange: PUT /api/v1/voice-ranges/{sessionId} + JSON body", async () => {
    fetchMock.mockResolvedValueOnce(json(sample));
    const body = {
      lowestNoteMidi: 50,
      highestNoteMidi: 74,
      sourceMethod: "MIC_MEASURE" as const,
    };
    await updateVoiceRange("sess-1", body);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toMatch(/\/api\/v1\/voice-ranges\/sess-1$/);
    expect((init as RequestInit).method).toBe("PUT");
    expect((init as RequestInit).body).toBe(JSON.stringify(body));
  });
});
