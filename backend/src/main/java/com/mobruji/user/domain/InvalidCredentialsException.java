package com.mobruji.user.domain;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 로그인 시 이메일 미존재 또는 비밀번호 불일치. 두 경우를 같은 401 + 동일 메시지로 통일해 어느 쪽이
 * 실패 원인인지 노출하지 않는다 (계정 열거 방어).
 */
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("invalid email or password");
    }
}
