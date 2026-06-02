package com.mobruji.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link GlobalExceptionHandler#maskIfSensitive(String, Object)} 단위 가드 — sensitive
 * field 마스킹 정책이 보안 요구 ({@code 04-security-policy.md} 익명 세션 룰) 와 일치하는지
 * 확인. directive 1509473362760695818 (2026-05-29) — 400 사유 노출 + sessionId 원문 보호
 * 양립.
 */
class GlobalExceptionHandlerTest {

    @Test
    @DisplayName("sessionId 는 rejectedValue 가 마스킹된다")
    void maskIfSensitive_sessionId_replacedByMask() {
        final Object masked = GlobalExceptionHandler.maskIfSensitive("sessionId", "sess_abc_xyz");

        assertThat(masked).isEqualTo("***");
    }

    @Test
    @DisplayName("sessionId 가 null 이면 null 그대로 (Bean Validation 메시지가 사유를 담음)")
    void maskIfSensitive_nullStaysNull() {
        final Object masked = GlobalExceptionHandler.maskIfSensitive("sessionId", null);

        assertThat(masked).isNull();
    }

    @Test
    @DisplayName("sensitive 가 아닌 field 는 원문 유지")
    void maskIfSensitive_nonSensitiveField_keepsValue() {
        final Object masked = GlobalExceptionHandler.maskIfSensitive("voiceRangeHigh", 200);

        assertThat(masked).isEqualTo(200);
    }
}
