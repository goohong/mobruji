package com.mobruji.web;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 모든 API 4xx/5xx 응답의 표준 본문.
 *
 * <p>Spring Boot 기본 {@code ErrorAttributes} 가 {@code server.error.include-message=never}
 * default 라 {@code MethodArgumentNotValidException} 의 field 사유가 응답에 노출되지
 * 않는 문제 (사용자 추천 화면 "Bad Request" 만 보이는 root cause, 2026-05-29 directive
 * 1509473362760695818) 를 해소한다.
 *
 * <p>본 응답 본문은 안전한 메시지만 노출한다 — {@code fieldErrors[].rejectedValue} 는
 * {@link GlobalExceptionHandler} 가 sensitive field (sessionId 등) 를 마스킹한 뒤 채운다
 * (보안 정책 {@code docs/ai-harness/04-security-policy.md}).
 */
public record ApiErrorResponse(
        OffsetDateTime timestamp,
        int status,
        String error,
        String message,
        String path,
        List<FieldErrorDetail> fieldErrors
) {

    public ApiErrorResponse {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(error, "error");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(path, "path");
        fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    /**
     * 개별 field 검증 실패 상세.
     *
     * <p>{@code rejectedValue} 는 호출자가 보낸 값. sessionId 등 보안 정책상 원문 노출 금지
     * field 는 {@link GlobalExceptionHandler#maskIfSensitive(String, Object)} 가 마스킹한다.
     */
    public record FieldErrorDetail(
            String field,
            String message,
            Object rejectedValue
    ) {

        public FieldErrorDetail {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(message, "message");
        }
    }
}
