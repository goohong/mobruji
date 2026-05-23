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

type Method = "GET" | "POST" | "PUT" | "DELETE";

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
  const init: RequestInit = {
    method,
    headers: {
      Accept: "application/json",
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
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
