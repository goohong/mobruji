/**
 * 백엔드 fetch 래퍼.
 *
 * - base URL: NEXT_PUBLIC_API_BASE_URL (없으면 http://localhost:8080).
 * - JSON 직렬화/역직렬화 일원화.
 * - 에러는 ApiError로 던진다(상태코드/응답 본문 보존).
 *
 * 도메인 함수(web/lib/api/voice-range.ts, recommendation.ts)는 이 래퍼만 사용한다.
 */

// trailing slash sanitize: env에 `https://api.example.com/` 처럼 들어와도
// path와 결합 시 double slash가 생기지 않도록 끝의 `/`를 제거한다 (#678).
export const API_BASE_URL = (
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080"
).replace(/\/+$/, "");

export class ApiError extends Error {
  readonly status: number;
  readonly body: unknown;

  constructor(status: number, message: string, body: unknown) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.body = body;
  }
}

type Method = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";

/**
 * 현재 인증 토큰을 제공하는 함수(없으면 null). 로그인 유지(#1768)를 위해
 * `AuthSessionRestorer` 가 마운트 시 auth store 와 연결해 등록한다 — 등록 전(SSR/
 * 미연결)에는 null provider 라 Bearer 를 붙이지 않는다(익명 흐름 호환).
 */
let authTokenProvider: () => string | null = () => null;

/**
 * apiFetch 가 모든 요청에 자동 첨부할 Authorization Bearer 토큰 공급자를 등록한다.
 *
 * - `web/lib/api` 집중 원칙: 도메인 함수는 토큰을 신경 쓰지 않고, 토큰 주입은 이
 *   provider 한 곳으로 일원화한다.
 * - 호출 측이 `Authorization` 헤더를 직접 지정하면 그 값이 우선한다(아래 spread 순서).
 */
export function setAuthTokenProvider(provider: () => string | null): void {
  authTokenProvider = provider;
}

type RequestOptions = {
  method?: Method;
  body?: unknown;
  signal?: AbortSignal;
  /**
   * 추가 요청 헤더. 예: session-bound endpoint 의 `X-Session-Id` 헤더
   * (PR #244 `SessionAuthGuard`). Accept/Content-Type 은 기본값이 덮어쓰지 않게
   * 호출 측 헤더가 마지막에 spread 된다.
   */
  headers?: Record<string, string>;
};

export async function apiFetch<TResponse>(
  path: string,
  options: RequestOptions = {},
): Promise<TResponse> {
  const { method = "GET", body, signal, headers } = options;

  const url = `${API_BASE_URL}${path}`;
  // 로그인 유지(#1768): provider 가 토큰을 주면 Authorization Bearer 를 자동 첨부한다.
  // 토큰이 없으면(익명/비로그인) 헤더를 추가하지 않아 익명 흐름과 호환된다. 호출 측이
  // headers 로 Authorization 을 직접 지정하면 마지막 spread 가 이겨 그 값이 우선한다.
  const bearerToken = authTokenProvider();
  const init: RequestInit = {
    method,
    headers: {
      Accept: "application/json",
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
      ...(bearerToken !== null ? { Authorization: `Bearer ${bearerToken}` } : {}),
      ...(headers ?? {}),
    },
    signal,
  };
  if (body !== undefined) {
    init.body = JSON.stringify(body);
  }

  const response = await fetch(url, init);

  if (response.status === 204) {
    return undefined as TResponse;
  }

  const contentType = response.headers.get("content-type") ?? "";
  const isJson = contentType.includes("application/json");
  const payload: unknown = isJson
    ? await response.json().catch(() => null)
    : await response.text().catch(() => null);

  if (!response.ok) {
    const message =
      isJson && payload && typeof payload === "object" && "message" in payload
        ? String((payload as { message: unknown }).message)
        : `Request failed: ${response.status} ${response.statusText}`;
    throw new ApiError(response.status, message, payload);
  }

  return payload as TResponse;
}
