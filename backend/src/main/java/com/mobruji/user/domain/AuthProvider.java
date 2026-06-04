package com.mobruji.user.domain;

/**
 * 사용자 계정의 인증 출처.
 *
 * <p>spec: 인증·음역대 프로필 영속 (#1491). {@code LOCAL} 은 이메일+비밀번호 회원가입, 나머지는 소셜
 * 로그인이다. 본 PR 은 {@code LOCAL} 흐름만 구현하고 {@code KAKAO}/{@code GOOGLE} 은 스키마/도메인
 * 수준에서만 예비한다 — 소셜 OAuth 교환 흐름은 후속 PR.
 */
public enum AuthProvider {

    LOCAL,
    KAKAO,
    GOOGLE,
}
