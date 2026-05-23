package com.mobruji.song.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * {@link SongNotFoundException} 회귀 가드.
 *
 * <p>글로벌 예외 핸들러가 별도로 등록돼 있지 않은 상태에서 컨트롤러가 본 예외를 던지면 {@link ResponseStatus} 어노테이션이
 * 그대로 HTTP 404 응답으로 변환된다. 어노테이션이 사라지면 500으로 회귀하므로 별도 가드한다.
 */
class SongNotFoundExceptionTest {

    @Test
    @DisplayName("메시지에 id를 포함한다")
    void message_containsId() {
        final SongNotFoundException songNotFoundException = new SongNotFoundException(123L);

        assertThat(songNotFoundException.getMessage()).contains("123");
    }

    @Test
    @DisplayName("@ResponseStatus(NOT_FOUND) 어노테이션이 붙어 있다")
    void responseStatus_isNotFound() {
        final ResponseStatus responseStatus = SongNotFoundException.class.getAnnotation(ResponseStatus.class);

        assertThat(responseStatus).isNotNull();
        assertThat(responseStatus.value()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("RuntimeException을 상속한다")
    void extendsRuntimeException() {
        assertThat(new SongNotFoundException(1L)).isInstanceOf(RuntimeException.class);
    }
}
