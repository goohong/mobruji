package com.mobruji.user.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    private final PasswordHasher passwordHasher = new PasswordHasher();

    @Test
    @DisplayName("hash 는 매 호출 새 salt 로 서로 다른 결과를 내고, 원문은 검증된다")
    void hash_producesSaltedEncoding_andMatches() {
        // given
        final String rawPassword = "correct-horse-battery";

        // when
        final String first = passwordHasher.hash(rawPassword);
        final String second = passwordHasher.hash(rawPassword);

        // then
        assertThat(first).isNotEqualTo(second);
        assertThat(first).startsWith(PasswordHasher.PREFIX + "$" + PasswordHasher.ITERATIONS + "$");
        assertThat(passwordHasher.matches(rawPassword, first)).isTrue();
        assertThat(passwordHasher.matches(rawPassword, second)).isTrue();
    }

    @Test
    @DisplayName("matches 는 다른 비밀번호를 거부한다")
    void matches_rejectsWrongPassword() {
        final String encoded = passwordHasher.hash("right-password");

        assertThat(passwordHasher.matches("wrong-password", encoded)).isFalse();
    }

    @Test
    @DisplayName("matches 는 인코딩 포맷이 깨지면 false")
    void matches_returnsFalseForMalformedHash() {
        assertThat(passwordHasher.matches("any", "not-a-valid-hash")).isFalse();
        assertThat(passwordHasher.matches("any", "pbkdf2-sha256$abc$xx$yy")).isFalse();
        assertThat(passwordHasher.matches("any", "bcrypt$1$aa$bb")).isFalse();
    }
}
