/**
 * apiFetch 래퍼 에러 매핑 경계 가드 (closes #595).
 *
 * 범위:
 *  - 4xx JSON 응답 → ApiError(status, message from body.message, body 보존)
 *  - 5xx 비-JSON 응답 → ApiError(status, fallback message, body=text)
 *  - network 실패 (fetch reject) → 원본 TypeError 그대로 propagate
 *  - AbortSignal abort → AbortError propagate (ApiError로 감싸지 않음)
 */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError, apiFetch } from "./client";

const fetchMock = vi.fn();
const originalFetch = globalThis.fetch;

beforeEach(() => {
  fetchMock.mockReset();
  globalThis.fetch = fetchMock as unknown as typeof fetch;
});

afterEach(() => {
  globalThis.fetch = originalFetch;
});

describe("apiFetch error mapping", () => {
  it("4xx JSON 응답은 ApiError로 변환되어 status/message/body가 보존된다", async () => {
    const body = { message: "voice range invalid", code: "VR-001" };
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify(body), {
        status: 400,
        headers: { "Content-Type": "application/json" },
      }),
    );

    await expect(apiFetch("/api/v1/voice-range")).rejects.toMatchObject({
      name: "ApiError",
      status: 400,
      message: "voice range invalid",
      body,
    });
  });

  it("5xx 비-JSON 응답은 fallback message + status를 가진 ApiError로 변환된다", async () => {
    fetchMock.mockResolvedValueOnce(
      new Response("Internal Server Error", {
        status: 500,
        statusText: "Internal Server Error",
        headers: { "Content-Type": "text/plain" },
      }),
    );

    const promise = apiFetch("/api/v1/recommendations");
    await expect(promise).rejects.toBeInstanceOf(ApiError);
    await expect(promise).rejects.toMatchObject({
      status: 500,
      message: "Request failed: 500 Internal Server Error",
    });
  });

  it("network 실패는 ApiError로 감싸지 않고 원본 에러를 그대로 던진다", async () => {
    const networkError = new TypeError("Failed to fetch");
    fetchMock.mockRejectedValueOnce(networkError);

    await expect(apiFetch("/api/v1/songs")).rejects.toBe(networkError);
  });

  it("AbortSignal로 취소된 요청은 AbortError를 그대로 propagate한다 (ApiError 아님)", async () => {
    const abortError = new DOMException("aborted", "AbortError");
    fetchMock.mockRejectedValueOnce(abortError);

    const controller = new AbortController();
    controller.abort();

    const promise = apiFetch("/api/v1/songs", { signal: controller.signal });
    await expect(promise).rejects.toBe(abortError);
    await expect(promise).rejects.not.toBeInstanceOf(ApiError);
  });
});
