package com.mobruji.user.domain;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 이미 가입된 이메일로 재가입을 시도할 때. 메시지에 이메일 원문을 담지 않는다 (열거 공격 정보 최소화).
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException() {
        super("email already registered");
    }
}
