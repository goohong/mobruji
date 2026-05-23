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
      body: "Internal Server Error",
    });
  });

  it.each([
    {
      label: "text/plain 4xx 본문은 ApiError.body 문자열로 보존된다",
      contentType: "text/plain; charset=utf-8",
      body: "rate limited: try later",
      status: 429,
      statusText: "Too Many Requests",
      expectedBody: "rate limited: try later",
    },
    {
      label: "text/html 게이트웨이 에러 본문도 string으로 보존된다",
      contentType: "text/html",
      body: "<html><body><h1>502 Bad Gateway</h1></body></html>",
      status: 502,
      statusText: "Bad Gateway",
      expectedBody: "<html><body><h1>502 Bad Gateway</h1></body></html>",
    },
    {
      label: "application/json인데 깨진 JSON이면 body=null fallback",
      contentType: "application/json",
      body: "{not json",
      status: 500,
      statusText: "Internal Server Error",
      expectedBody: null,
    },
  ])("$label", async ({ contentType, body, status, statusText, expectedBody }) => {
    fetchMock.mockResolvedValueOnce(
      new Response(body, { status, statusText, headers: { "Content-Type": contentType } }),
    );

    await expect(apiFetch("/api/v1/probe")).rejects.toMatchObject({
      name: "ApiError",
      status,
      message: `Request failed: ${status} ${statusText}`,
      body: expectedBody,
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

describe("apiFetch NEXT_PUBLIC_API_BASE_URL 분기", () => {
  // API_BASE_URL은 module top-level에서 env 평가 → env 조작 후 dynamic import 필요.
  const originalEnv = process.env.NEXT_PUBLIC_API_BASE_URL;
  afterEach(() => {
    if (originalEnv === undefined) delete process.env.NEXT_PUBLIC_API_BASE_URL;
    else process.env.NEXT_PUBLIC_API_BASE_URL = originalEnv;
  });

  it.each([
    { label: "env 미설정 → default localhost:8080", env: undefined, expected: "http://localhost:8080/api/v1/songs", base: "http://localhost:8080" },
    { label: "env 정상 URL → 그대로 base + path 정확 결합", env: "https://api.example.com", expected: "https://api.example.com/api/v1/songs", base: "https://api.example.com" },
    { label: "env trailing slash → sanitize 후 single slash 결합 (#678)", env: "https://api.example.com/", expected: "https://api.example.com/api/v1/songs", base: "https://api.example.com" },
    { label: "env 다중 trailing slash → 모두 sanitize", env: "https://api.example.com///", expected: "https://api.example.com/api/v1/songs", base: "https://api.example.com" },
  ])("$label", async ({ env, expected, base }) => {
    vi.resetModules();
    if (env === undefined) delete process.env.NEXT_PUBLIC_API_BASE_URL;
    else process.env.NEXT_PUBLIC_API_BASE_URL = env;
    const { apiFetch: scopedApiFetch, API_BASE_URL } = await import("./client");
    fetchMock.mockResolvedValueOnce(new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } }));

    await scopedApiFetch("/api/v1/songs");

    expect(API_BASE_URL).toBe(base);
    expect(fetchMock).toHaveBeenCalledWith(expected, expect.objectContaining({ method: "GET" }));
  });
});

describe("apiFetch path leading-slash 가드 (#680)", () => {
  // 현 동작 lock: client.ts는 path를 normalize 하지 않는다.
  // 호출자가 leading `/`를 빠뜨리면 비정상 URL이 생성된다는 사실을 회귀 테스트로 명시.
  it.each([
    { label: "leading `/` 있는 path → base와 single slash로 결합 (정상)", path: "/api/v1/songs", expected: "http://localhost:8080/api/v1/songs" },
    { label: "leading `/` 없는 path → base 끝과 path 시작이 그대로 붙는다 (호출자 책임)", path: "api/v1/songs", expected: "http://localhost:8080api/v1/songs" },
    { label: "빈 path → base URL 그대로 호출", path: "", expected: "http://localhost:8080" },
  ])("$label", async ({ path, expected }) => {
    fetchMock.mockResolvedValueOnce(new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } }));
    await apiFetch(path);
    expect(fetchMock).toHaveBeenCalledWith(expected, expect.objectContaining({ method: "GET" }));
  });
});
