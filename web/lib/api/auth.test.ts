/**
 * 인증 API 클라이언트 단위 테스트 (closes #1768).
 *
 * 범위:
 *  - signup / login → 정확한 경로·메서드·body 로 POST.
 *  - fetchMe / updateProfile → Authorization Bearer 헤더(명시 토큰 인자) 첨부.
 *  - 토큰 인자 생략 시 명시 Authorization 헤더를 붙이지 않음(client provider 위임).
 */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { fetchMe, login, signup, updateProfile } from "./auth";
import { setAuthTokenProvider } from "./client";

const fetchMock = vi.fn();
const originalFetch = globalThis.fetch;

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

beforeEach(() => {
  fetchMock.mockReset();
  globalThis.fetch = fetchMock as unknown as typeof fetch;
  // 다른 테스트가 등록한 provider 가 leak 되지 않도록 매번 null provider 로 reset.
  setAuthTokenProvider(() => null);
});

afterEach(() => {
  globalThis.fetch = originalFetch;
  setAuthTokenProvider(() => null);
});

describe("signup", () => {
  it("POST /api/v1/users/signup 에 body 를 그대로 보낸다", async () => {
    const auth = {
      userId: 1,
      email: "a@b.com",
      token: "tok",
      tokenExpiresAt: "2999-01-01T00:00:00",
    };
    fetchMock.mockResolvedValueOnce(jsonResponse(auth, 201));

    const result = await signup({ email: "a@b.com", password: "pw12345678" });

    expect(result).toEqual(auth);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://localhost:8080/api/v1/users/signup");
    expect(init.method).toBe("POST");
    expect(init.body).toBe(
      JSON.stringify({ email: "a@b.com", password: "pw12345678" }),
    );
  });
});

describe("login", () => {
  it("POST /api/v1/users/login 에 자격을 보낸다", async () => {
    const auth = {
      userId: 2,
      email: "c@d.com",
      token: "tok2",
      tokenExpiresAt: "2999-01-01T00:00:00",
    };
    fetchMock.mockResolvedValueOnce(jsonResponse(auth));

    const result = await login({ email: "c@d.com", password: "secret-pw-1" });

    expect(result).toEqual(auth);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://localhost:8080/api/v1/users/login");
    expect(init.method).toBe("POST");
  });
});

describe("fetchMe", () => {
  it("토큰 인자를 주면 Authorization Bearer 헤더로 GET 한다", async () => {
    const profile = {
      userId: 5,
      email: "me@x.com",
      authProvider: "LOCAL",
      gender: "MALE",
      vocalRangeLowMidi: 48,
      vocalRangeHighMidi: 72,
    };
    fetchMock.mockResolvedValueOnce(jsonResponse(profile));

    const result = await fetchMe("tok-me");

    expect(result).toEqual(profile);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://localhost:8080/api/v1/users/me");
    expect(init.method).toBe("GET");
    expect((init.headers as Record<string, string>).Authorization).toBe(
      "Bearer tok-me",
    );
  });

  it("토큰 인자를 생략하면 명시 Authorization 헤더를 붙이지 않는다", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ userId: 1 }));

    await fetchMe();

    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect((init.headers as Record<string, string>).Authorization).toBeUndefined();
  });
});

describe("updateProfile", () => {
  it("PATCH /api/v1/users/me 에 body + Bearer 헤더로 갱신한다", async () => {
    const profile = {
      userId: 5,
      email: "me@x.com",
      authProvider: "LOCAL",
      gender: "FEMALE",
      vocalRangeLowMidi: 50,
      vocalRangeHighMidi: 70,
    };
    fetchMock.mockResolvedValueOnce(jsonResponse(profile));

    const result = await updateProfile(
      { gender: "FEMALE", vocalRangeLowMidi: 50, vocalRangeHighMidi: 70 },
      "tok-patch",
    );

    expect(result).toEqual(profile);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://localhost:8080/api/v1/users/me");
    expect(init.method).toBe("PATCH");
    expect((init.headers as Record<string, string>).Authorization).toBe(
      "Bearer tok-patch",
    );
    expect(init.body).toBe(
      JSON.stringify({
        gender: "FEMALE",
        vocalRangeLowMidi: 50,
        vocalRangeHighMidi: 70,
      }),
    );
  });
});
