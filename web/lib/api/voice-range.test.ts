/**
 * voice-range API 클라이언트 단위 가드 (PR #600 짝).
 *
 * - create: POST body 직렬화 + 201 응답 매핑.
 * - read:   GET path encoding.
 * - update: PUT body 직렬화.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "./client";
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

  it("readVoiceRange: 404 응답 시 ApiError(status=404) 전파", async () => {
    fetchMock.mockResolvedValueOnce(json({ message: "not found" }, 404));
    const p = readVoiceRange("sess-x");
    await expect(p).rejects.toBeInstanceOf(ApiError);
    await expect(p).rejects.toMatchObject({ status: 404 });
  });

  it("readVoiceRange: 5xx 응답 시 ApiError 전파", async () => {
    fetchMock.mockResolvedValueOnce(json({}, 503));
    const p = readVoiceRange("sess-1");
    await expect(p).rejects.toBeInstanceOf(ApiError);
    await expect(p).rejects.toMatchObject({ status: 503 });
  });

  it("readVoiceRange: AbortSignal을 fetch init으로 전달 + abort reject 전파", async () => {
    fetchMock.mockRejectedValueOnce(new DOMException("Aborted", "AbortError"));
    const controller = new AbortController();
    controller.abort();
    await expect(
      readVoiceRange("sess-1", { signal: controller.signal }),
    ).rejects.toMatchObject({ name: "AbortError" });
    expect((fetchMock.mock.calls[0][1] as RequestInit).signal).toBe(
      controller.signal,
    );
  });

  it("readVoiceRange: 최소 응답(필수 필드)을 그대로 매핑한다", async () => {
    fetchMock.mockResolvedValueOnce(json(sample));
    const result = await readVoiceRange("sess-1");
    expect(result).toEqual(sample);
  });

  const baseReq = {
    sessionId: "sess-1",
    lowestNoteMidi: 48,
    highestNoteMidi: 72,
    sourceMethod: "SELF_REPORT" as const,
  };

  it("createVoiceRange: 400 응답 시 ApiError(status/message/body) 전파", async () => {
    const errBody = { message: "lowestNoteMidi must be < highestNoteMidi" };
    fetchMock.mockResolvedValueOnce(json(errBody, 400));
    await expect(createVoiceRange(baseReq)).rejects.toMatchObject({
      name: "ApiError",
      status: 400,
      message: errBody.message,
      body: errBody,
    });
  });

  it("createVoiceRange: 5xx 응답 시 ApiError 전파", async () => {
    fetchMock.mockResolvedValueOnce(json({}, 503));
    const p = createVoiceRange(baseReq);
    await expect(p).rejects.toBeInstanceOf(ApiError);
    await expect(p).rejects.toMatchObject({ status: 503 });
  });

  it("createVoiceRange: AbortSignal을 fetch init으로 전달 + abort reject 전파", async () => {
    fetchMock.mockRejectedValueOnce(new DOMException("Aborted", "AbortError"));
    const controller = new AbortController();
    controller.abort();
    await expect(
      createVoiceRange(baseReq, { signal: controller.signal }),
    ).rejects.toMatchObject({ name: "AbortError" });
    expect((fetchMock.mock.calls[0][1] as RequestInit).signal).toBe(
      controller.signal,
    );
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

  const updateBody = {
    lowestNoteMidi: 50,
    highestNoteMidi: 74,
    sourceMethod: "MIC_MEASURE" as const,
  };

  it("updateVoiceRange: 404 응답 시 ApiError(status=404) 전파", async () => {
    fetchMock.mockResolvedValueOnce(json({ message: "not found" }, 404));
    await expect(updateVoiceRange("sess-x", updateBody)).rejects.toMatchObject({
      name: "ApiError",
      status: 404,
    });
  });

  it("updateVoiceRange: 409 응답 시 ApiError(status=409) 전파", async () => {
    fetchMock.mockResolvedValueOnce(json({ message: "conflict" }, 409));
    const p = updateVoiceRange("sess-1", updateBody);
    await expect(p).rejects.toBeInstanceOf(ApiError);
    await expect(p).rejects.toMatchObject({ status: 409 });
  });

  it("updateVoiceRange: 5xx 응답 시 ApiError 전파", async () => {
    fetchMock.mockResolvedValueOnce(json({}, 503));
    const p = updateVoiceRange("sess-1", updateBody);
    await expect(p).rejects.toBeInstanceOf(ApiError);
    await expect(p).rejects.toMatchObject({ status: 503 });
  });

  it("updateVoiceRange: AbortSignal을 fetch init으로 전달 + abort reject 전파", async () => {
    fetchMock.mockRejectedValueOnce(new DOMException("Aborted", "AbortError"));
    const controller = new AbortController();
    controller.abort();
    await expect(
      updateVoiceRange("sess-1", updateBody, { signal: controller.signal }),
    ).rejects.toMatchObject({ name: "AbortError" });
    expect((fetchMock.mock.calls[0][1] as RequestInit).signal).toBe(
      controller.signal,
    );
  });
});
