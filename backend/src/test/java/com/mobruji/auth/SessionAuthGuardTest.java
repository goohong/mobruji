package com.mobruji.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@link SessionAuthGuard} 단위 테스트 — session-bound endpoint 인증 매트릭스 (rev 16 / #238).
 */
class SessionAuthGuardTest {

    private final SessionAuthGuard sessionAuthGuard = new SessionAuthGuard();

    @Test
    @DisplayName("path 와 헤더가 일치하면 예외 없음")
    void verify_matching_passes() {
        assertThatCode(() -> sessionAuthGuard.verify("session-A", "session-A"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("헤더 누락(null) → 401")
    void verify_nullHeader_throws401() {
        assertThatThrownBy(() -> sessionAuthGuard.verify("session-A", null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(throwable -> ((ResponseStatusException) throwable).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("헤더 blank → 401")
    void verify_blankHeader_throws401() {
        assertThatThrownBy(() -> sessionAuthGuard.verify("session-A", "   "))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(throwable -> ((ResponseStatusException) throwable).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("path 와 헤더 값 불일치 → 401 (다른 세션 히스토리 노출 차단)")
    void verify_mismatch_throws401() {
        assertThatThrownBy(() -> sessionAuthGuard.verify("session-A", "session-B"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(throwable -> ((ResponseStatusException) throwable).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("path 자체가 null/blank 인 비정상 호출도 → 401 (방어적)")
    void verify_blankPath_throws401() {
        assertThatThrownBy(() -> sessionAuthGuard.verify(null, "session-A"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(throwable -> ((ResponseStatusException) throwable).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThatThrownBy(() -> sessionAuthGuard.verify("", "session-A"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(throwable -> ((ResponseStatusException) throwable).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("UTF-8 한글 sessionId 도 정상 비교")
    void verify_utf8_matching_passes() {
        assertThatCode(() -> sessionAuthGuard.verify("세션-가나다", "세션-가나다"))
                .doesNotThrowAnyException();
    }
}
