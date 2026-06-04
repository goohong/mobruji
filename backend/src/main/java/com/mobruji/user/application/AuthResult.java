package com.mobruji.user.application;

import com.mobruji.user.domain.User;

/**
 * 회원가입/로그인 결과 — 인증된 사용자 + 새로 발급된 토큰. 컨트롤러가 응답 DTO 로 매핑한다.
 */
public record AuthResult(
        User user,
        UserAuthTokenService.IssuedToken token
) {
}
