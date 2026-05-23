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

describe("apiFetch headers spread + 204 + body 가드 (#685)", () => {
  // 호출자 헤더가 마지막에 spread → 기본 Accept/Content-Type 을 덮어쓰거나 추가 헤더 부여.
  it("Accept 호출자 override", async () => {
    fetchMock.mockResolvedValueOnce(new Response("ok", { status: 200, headers: { "Content-Type": "text/plain" } }));
    await apiFetch("/api/v1/probe", { headers: { Accept: "text/plain" } });
    expect((fetchMock.mock.calls[0][1] as RequestInit).headers).toMatchObject({ Accept: "text/plain" });
  });

  it("커스텀 X-Session-Id 추가 + 기본 Accept 유지", async () => {
    fetchMock.mockResolvedValueOnce(new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } }));
    await apiFetch("/api/v1/me", { headers: { "X-Session-Id": "sess-42" } });
    expect((fetchMock.mock.calls[0][1] as RequestInit).headers).toMatchObject({ "X-Session-Id": "sess-42", Accept: "application/json" });
  });

  it("body 있는 POST에서 호출자 Content-Type override", async () => {
    fetchMock.mockResolvedValueOnce(new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } }));
    await apiFetch("/api/v1/upload", { method: "POST", body: "raw", headers: { "Content-Type": "text/plain" } });
    expect((fetchMock.mock.calls[0][1] as RequestInit).headers).toMatchObject({ "Content-Type": "text/plain" });
  });

  it("204 No Content → body 파싱 skip + undefined 리턴", async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));
    expect(await apiFetch<void>("/api/v1/sessions/abc", { method: "DELETE" })).toBeUndefined();
  });

  it("body 있으면 JSON.stringify + Content-Type=application/json 자동 부여", async () => {
    fetchMock.mockResolvedValueOnce(new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } }));
    const body = { lowestNote: "C3", highestNote: "G4" };
    await apiFetch("/api/v1/voice-range", { method: "POST", body });
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect((init.headers as Record<string, string>)["Content-Type"]).toBe("application/json");
    expect(init.body).toBe(JSON.stringify(body));
  });
});

describe("ApiError JSON.stringify 보존 가드 (#689)", () => {
  // 운영 로그/Sentry payload 에서 ApiError 가 어떤 필드를 보존하는지 동작 lock.
  // - Error.prototype.message 는 non-enumerable → 직렬화 결과에 포함되지 않는다.
  // - status/body 는 인스턴스 필드(enumerable) → 직렬화·round-trip 보존.
  it("string body: status/body round-trip 보존, message 는 제외", () => {
    const error = new ApiError(404, "Not found", "raw text");
    const parsed = JSON.parse(JSON.stringify(error)) as Record<string, unknown>;
    expect(parsed).toMatchObject({ status: 404, body: "raw text" });
    expect(parsed.message).toBeUndefined();
  });

  it("object body: round-trip 후 객체 동등성 유지", () => {
    const body = { error: "x", details: [1, 2, 3] };
    const error = new ApiError(500, "boom", body);
    const parsed = JSON.parse(JSON.stringify(error)) as Record<string, unknown>;
    expect(parsed).toEqual({ status: 500, body });
  });
});

describe("apiFetch signal pass-through 가드 (#692)", () => {
  // RequestOptions.signal이 fetch init.signal로 그대로 전달되는지 lock.
  // 기존 abort 테스트(L108-118)는 fetch reject만 검증 → init 인스턴스 전달은 미검증이었음.
  it("정상(non-aborted) signal → fetch init.signal에 동일 인스턴스 전달", async () => {
    fetchMock.mockResolvedValueOnce(new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } }));
    const controller = new AbortController();
    await apiFetch("/api/v1/songs", { signal: controller.signal });
    expect((fetchMock.mock.calls[0][1] as RequestInit).signal).toBe(controller.signal);
  });

  it("abort된 signal → init.signal에 동일 인스턴스 전달 + AbortError 전파", async () => {
    const abortError = new DOMException("aborted", "AbortError");
    fetchMock.mockRejectedValueOnce(abortError);
    const controller = new AbortController();
    controller.abort();
    await expect(apiFetch("/api/v1/songs", { signal: controller.signal })).rejects.toBe(abortError);
    expect((fetchMock.mock.calls[0][1] as RequestInit).signal).toBe(controller.signal);
  });

  it("signal 미지정 → init.signal === undefined", async () => {
    fetchMock.mockResolvedValueOnce(new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } }));
    await apiFetch("/api/v1/songs");
    expect((fetchMock.mock.calls[0][1] as RequestInit).signal).toBeUndefined();
  });
});

type RequestOptionsForTest = Parameters<typeof apiFetch>[1];

describe("apiFetch body=null vs undefined 분기 가드 (#695)", () => {
  // 현 동작 lock: client.ts는 `body !== undefined`만 체크.
  // → body=null은 JSON.stringify(null) = "null" 전송 + Content-Type 부여.
  // → body 미지정은 init.body 없음 + Content-Type 없음.
  it.each([
    { label: "body 미지정 → init.body undefined, Content-Type 헤더 없음", options: {}, expectedBody: undefined, expectedHasContentType: false },
    { label: "body=null → init.body === 'null' 문자열, Content-Type=application/json", options: { method: "POST" as const, body: null }, expectedBody: "null", expectedHasContentType: true },
    { label: "body={} → init.body === '{}', Content-Type=application/json", options: { method: "POST" as const, body: {} }, expectedBody: "{}", expectedHasContentType: true },
  ])("$label", async ({ options, expectedBody, expectedHasContentType }: { options: RequestOptionsForTest; expectedBody: string | undefined; expectedHasContentType: boolean }) => {
    fetchMock.mockResolvedValueOnce(new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } }));
    await apiFetch("/api/v1/probe", options);
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect(init.body).toBe(expectedBody);
    const headers = init.headers as Record<string, string>;
    expect("Content-Type" in headers).toBe(expectedHasContentType);
  });
});

describe("apiFetch path query string 가드 (#683)", () => {
  // 현 동작 lock: client.ts는 URL/URLSearchParams 변환 없이 path를 raw concat한다.
  // → query string은 호출자가 미리 조립·인코딩한 형태 그대로 fetch URL에 전달된다.
  it.each([
    { label: "단일 `?key=val` → raw concat", path: "/api/v1/songs?q=love", expected: "http://localhost:8080/api/v1/songs?q=love" },
    { label: "다중 query `?a=1&b=2` → 구분자 보존", path: "/api/v1/songs?genre=ballad&limit=10", expected: "http://localhost:8080/api/v1/songs?genre=ballad&limit=10" },
    { label: "한글 query 미인코딩 → 호출자가 encode하지 않으면 그대로 붙는다 (인코딩은 호출자 책임)", path: "/api/v1/songs?q=발라드", expected: "http://localhost:8080/api/v1/songs?q=발라드" },
    { label: "한글 query encodeURIComponent → 인코딩된 형태 보존", path: `/api/v1/songs?q=${encodeURIComponent("발라드")}`, expected: "http://localhost:8080/api/v1/songs?q=%EB%B0%9C%EB%9D%BC%EB%93%9C" },
  ])("$label", async ({ path, expected }: { path: string; expected: string }) => {
    fetchMock.mockResolvedValueOnce(new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } }));
    await apiFetch(path);
    expect(fetchMock).toHaveBeenCalledWith(expected, expect.objectContaining({ method: "GET" }));
  });
});

describe("apiFetch falsy primitive body 가드 (#702)", () => {
  // 현 동작 lock: body 처리 분기는 `body !== undefined` 단일 조건이므로 falsy primitive 도
  // JSON.stringify 를 통과해 init.body 와 Content-Type 이 동일하게 부여된다.
  it.each([
    { label: "body=0 → '0' stringify + Content-Type", body: 0 as unknown, expectedBody: "0" },
    { label: 'body="" → \'""\' stringify + Content-Type', body: "" as unknown, expectedBody: '""' },
    { label: "body=false → 'false' stringify + Content-Type", body: false as unknown, expectedBody: "false" },
  ])("$label", async ({ body, expectedBody }: { body: unknown; expectedBody: string }) => {
    fetchMock.mockResolvedValueOnce(new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } }));
    await apiFetch("/api/v1/probe", { method: "POST", body });
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect(init.body).toBe(expectedBody);
    expect((init.headers as Record<string, string>)["Content-Type"]).toBe("application/json");
  });
});

describe("apiFetch 200 OK 빈 body 분기 가드 (#702)", () => {
  // 204 는 short-circuit 되지만 200 + 빈 body 는 contentType 분기에 따라 처리된다.
  it("200 + application/json + 빈 본문 → JSON.parse 실패 catch → null fallback", async () => {
    fetchMock.mockResolvedValueOnce(new Response("", { status: 200, headers: { "Content-Type": "application/json" } }));
    await expect(apiFetch("/api/v1/probe")).resolves.toBeNull();
  });

  it("200 + text/plain + 빈 본문 → text() 빈 문자열 그대로 리턴", async () => {
    fetchMock.mockResolvedValueOnce(new Response("", { status: 200, headers: { "Content-Type": "text/plain" } }));
    await expect(apiFetch<string>("/api/v1/probe")).resolves.toBe("");
  });
});

describe("apiFetch Response header case-insensitivity 가드 (#706)", () => {
  // Fetch spec 상 Headers.get 은 case-insensitive 매칭.
  // 백엔드가 `Content-Type` / `content-type` 어떤 표기로 응답해도 JSON 분기가 동일하게 적용됨을 lock.
  it.each([
    { label: "Content-Type (canonical) → JSON 파싱", headerKey: "Content-Type" },
    { label: "content-type (lower) → JSON 파싱", headerKey: "content-type" },
    { label: "CONTENT-TYPE (upper) → JSON 파싱", headerKey: "CONTENT-TYPE" },
  ])("$label", async ({ headerKey }: { headerKey: string }) => {
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({ ok: true }), { status: 200, headers: { [headerKey]: "application/json" } }),
    );
    await expect(apiFetch<{ ok: boolean }>("/api/v1/probe")).resolves.toEqual({ ok: true });
  });
});

describe("apiFetch non-falsy primitive/Array body 가드 (#706)", () => {
  // #702 는 falsy primitive (0, "", false) 만 lock → non-falsy 분기 보강.
  // body 처리 분기는 `body !== undefined` 단일 조건이므로 Array/number(42)/true 모두
  // JSON.stringify 통과 + Content-Type=application/json 자동 부여.
  it.each([
    { label: "body=Array → JSON.stringify 배열", body: [1, 2, 3] as unknown, expectedBody: "[1,2,3]" },
    { label: "body=number(42) → '42' stringify", body: 42 as unknown, expectedBody: "42" },
    { label: "body=true → 'true' stringify", body: true as unknown, expectedBody: "true" },
  ])("$label", async ({ body, expectedBody }: { body: unknown; expectedBody: string }) => {
    fetchMock.mockResolvedValueOnce(new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } }));
    await apiFetch("/api/v1/probe", { method: "POST", body });
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect(init.body).toBe(expectedBody);
    expect((init.headers as Record<string, string>)["Content-Type"]).toBe("application/json");
  });
});

describe("apiFetch non-GET 메서드 body 처리 가드 (#699)", () => {
  // 현 동작 lock: body 처리 분기는 method 와 무관하게 `body !== undefined` 단일 조건.
  // PUT/DELETE 도 body 가 있으면 POST 와 동일하게 JSON.stringify + Content-Type 자동 부여,
  // DELETE without body 는 init.body 없음 + Content-Type 없음 (#685 의 204 케이스 보강).
  it.each([
    { label: "PUT + body → JSON.stringify + Content-Type", method: "PUT" as const, body: { id: 1, name: "x" }, expectedBody: '{"id":1,"name":"x"}', expectedHasContentType: true },
    { label: "DELETE + body → JSON.stringify + Content-Type", method: "DELETE" as const, body: { reason: "duplicate" }, expectedBody: '{"reason":"duplicate"}', expectedHasContentType: true },
    { label: "DELETE without body → init.body 없음, Content-Type 없음", method: "DELETE" as const, body: undefined, expectedBody: undefined, expectedHasContentType: false },
  ])("$label", async ({ method, body, expectedBody, expectedHasContentType }) => {
    fetchMock.mockResolvedValueOnce(new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } }));
    await apiFetch("/api/v1/probe", { method, body });
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect(init.method).toBe(method);
    expect(init.body).toBe(expectedBody);
    const headers = init.headers as Record<string, string>;
    expect("Content-Type" in headers).toBe(expectedHasContentType);
  });
});
