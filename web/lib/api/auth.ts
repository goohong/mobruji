/**
 * 인증 API 클라이언트 — 회원가입/로그인/세션복원/프로필 갱신.
 *
 * BE 컨트롤러 `UserAuthController` / `UserProfileController` 와 1:1 매칭 (#1491):
 *   POST  /api/v1/users/signup → signup    (201, 이메일 중복 409)
 *   POST  /api/v1/users/login  → login     (200, 자격 불일치 401)
 *   GET   /api/v1/users/me     → fetchMe    (Authorization: Bearer, 401 무효)
 *   PATCH /api/v1/users/me     → updateMe   (Authorization: Bearer)
 *
 * 토큰은 평소 `setAuthTokenProvider`(client.ts) 로 자동 첨부되지만, 자동로그인 부트
 * 시점처럼 provider 연결 전이거나 특정 토큰을 강제할 때를 위해 `token` 인자를 받아
 * 명시 Authorization 헤더를 붙일 수 있게 둔다(인자가 우선).
 *
 * 타입은 BE record DTO 와 동일 필드명을 유지한다 (camelCase).
 */

import { apiFetch } from "./client";

export type UserGender = "MALE" | "FEMALE" | "UNSPECIFIED";

export type AuthProvider = "LOCAL" | "KAKAO" | "GOOGLE";

/** BE `SignupRequest`. 음역대는 동시 입력 또는 동시 미입력 + low<=high. */
export type SignupRequest = {
  email: string;
  password: string;
  gender?: UserGender;
  vocalRangeLowMidi?: number;
  vocalRangeHighMidi?: number;
};

/** BE `LoginRequest`. */
export type LoginRequest = {
  email: string;
  password: string;
};

/** BE `AuthResponse` — 발급 토큰(원문, 1회 노출) + 만료 시각 + 사용자 식별. */
export type AuthResponse = {
  userId: number;
  email: string;
  token: string;
  tokenExpiresAt: string;
};

/** BE `UserProfileResponse` — 영속된 음역대/성별 프로필. */
export type UserProfileResponse = {
  userId: number;
  email: string;
  authProvider: AuthProvider;
  gender: UserGender | null;
  vocalRangeLowMidi: number | null;
  vocalRangeHighMidi: number | null;
};

/** BE `UpdateProfileRequest`. 모든 필드 선택. */
export type UpdateProfileRequest = {
  gender?: UserGender;
  vocalRangeLowMidi?: number;
  vocalRangeHighMidi?: number;
};

function bearerHeader(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}` };
}

export function signup(
  request: SignupRequest,
  options: { signal?: AbortSignal } = {},
): Promise<AuthResponse> {
  return apiFetch<AuthResponse>("/api/v1/users/signup", {
    method: "POST",
    body: request,
    signal: options.signal,
  });
}

export function login(
  request: LoginRequest,
  options: { signal?: AbortSignal } = {},
): Promise<AuthResponse> {
  return apiFetch<AuthResponse>("/api/v1/users/login", {
    method: "POST",
    body: request,
    signal: options.signal,
  });
}

/**
 * 현재 토큰으로 프로필을 조회한다(자동로그인 세션복원).
 *
 * `token` 인자를 주면 명시 Authorization 헤더를 붙인다 — provider 등록 전 부트 시점에
 * 영속 토큰을 직접 전달하는 경로. 생략하면 client.ts provider 의 자동 첨부에 맡긴다.
 */
export function fetchMe(
  token?: string,
  options: { signal?: AbortSignal } = {},
): Promise<UserProfileResponse> {
  return apiFetch<UserProfileResponse>("/api/v1/users/me", {
    signal: options.signal,
    headers: token !== undefined ? bearerHeader(token) : undefined,
  });
}

export function updateProfile(
  request: UpdateProfileRequest,
  token?: string,
  options: { signal?: AbortSignal } = {},
): Promise<UserProfileResponse> {
  return apiFetch<UserProfileResponse>("/api/v1/users/me", {
    method: "PATCH",
    body: request,
    signal: options.signal,
    headers: token !== undefined ? bearerHeader(token) : undefined,
  });
}
