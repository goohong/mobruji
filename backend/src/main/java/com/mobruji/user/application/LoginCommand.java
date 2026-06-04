package com.mobruji.user.application;

import java.util.Objects;

/**
 * 이메일 로그인 입력.
 */
public record LoginCommand(
        String email,
        String rawPassword
) {

    public LoginCommand {
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(rawPassword, "rawPassword must not be null");
    }
}
