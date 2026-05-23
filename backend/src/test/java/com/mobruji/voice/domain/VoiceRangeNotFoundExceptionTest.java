package com.mobruji.voice.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * {@link VoiceRangeNotFoundException} 회귀 가드.
 *
 * <p>Spring MVC 글로벌 핸들러가 별도 등록되지 않은 상태에서 컨트롤러가 본 예외를 던지면
 * {@link ResponseStatus} 어노테이션이 그대로 HTTP 404 응답으로 변환된다. 이 어노테이션이 사라지면 500으로 회귀하므로 가드한다.
 */
class VoiceRangeNotFoundExceptionTest {

    @Test
    @DisplayName("메시지에 sessionId를 포함한다")
    void message_containsSessionId() {
        final VoiceRangeNotFoundException voiceRangeNotFoundException = new VoiceRangeNotFoundException("session-x");
        assertThat(voiceRangeNotFoundException.getMessage()).contains("session-x");
    }

    @Test
    @DisplayName("@ResponseStatus(NOT_FOUND) 어노테이션이 붙어 있다")
    void responseStatus_isNotFound() {
        final ResponseStatus responseStatus = VoiceRangeNotFoundException.class.getAnnotation(ResponseStatus.class);
        assertThat(responseStatus).isNotNull();
        assertThat(responseStatus.value()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("RuntimeException을 상속한다")
    void extendsRuntimeException() {
        assertThat(new VoiceRangeNotFoundException("s")).isInstanceOf(RuntimeException.class);
    }
}
