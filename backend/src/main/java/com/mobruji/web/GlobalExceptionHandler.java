package com.mobruji.web;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import lombok.extern.slf4j.Slf4j;

/**
 * 전역 4xx 예외 매핑.
 *
 * <p>directive 1509473362760695818 (2026-05-29) — 사용자가 추천 API 400 사유를 못 보던 문제
 * (Spring Boot default 응답 본문에 message/fieldErrors 누락) 해소. 본 handler 가 적용된
 * 후 fe 의 {@code ApiError.message} 는 "voiceRangeHigh: 119 보다 작거나 같아야 합니다"
 * 처럼 사용자가 즉시 무엇이 잘못됐는지 알 수 있는 메시지를 받는다.
 *
 * <p>스코프: API 4xx 만 핸들 (validation/payload 파싱). 5xx / 도메인 예외
 * ({@code RecommendationNotFoundException} 등 {@code @ResponseStatus} 보유) 는 Spring
 * 기본 핸들러가 처리하던 동작을 그대로 유지한다 — 본 PR 은 사용자 직격 400 시야 확보가
 * 단일 목적.
 *
 * <p>보안: {@link #SENSITIVE_FIELD_NAMES} 의 field 는 rejectedValue 를 {@code "***"} 로
 * 마스킹한다 (sessionId 원문 로그/응답 노출 금지, {@code 04-security-policy.md}).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 응답/로그에 원문 노출이 금지된 field name 집합.
     *
     * <p>현재 후보:
     * <ul>
     * <li>{@code sessionId} — 익명 세션 식별자, 노출 시 cross-session linkability 위험</li>
     * </ul>
     * 새 sensitive field 가 생기면 본 집합에 추가한다.
     */
    private static final Set<String> SENSITIVE_FIELD_NAMES = Set.of("sessionId");

    private static final String MASK = "***";

    /**
     * Bean Validation 실패 ({@code @Valid @RequestBody}) → 400.
     *
     * <p>fieldErrors 에 field name / 사유 메시지 / (마스킹된) rejectedValue 를 담아 사용자가
     * 어느 입력이 왜 거부됐는지 즉시 파악할 수 있게 한다. message 헤드라인은 첫 field 사유 +
     * (외 N건) 형식.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            final MethodArgumentNotValidException methodArgumentNotValidException,
            final WebRequest webRequest) {
        final List<ApiErrorResponse.FieldErrorDetail> fieldErrors = new ArrayList<>();
        for (final FieldError fieldError : methodArgumentNotValidException.getBindingResult().getFieldErrors()) {
            final String fieldName = fieldError.getField();
            final String message = resolveMessage(fieldError);
            final Object rejectedValue = maskIfSensitive(fieldName, fieldError.getRejectedValue());
            fieldErrors.add(new ApiErrorResponse.FieldErrorDetail(fieldName, message, rejectedValue));
        }
        final String headline = composeHeadline(fieldErrors);
        log.warn("validation 실패 — fieldErrors={}",
                fieldErrors.stream().map(detail -> detail.field() + ":" + detail.message()).toList());
        return buildResponse(HttpStatus.BAD_REQUEST, headline, webRequest, fieldErrors);
    }

    /**
     * payload 파싱 실패 — malformed JSON / unknown enum 값 / 타입 mismatch → 400.
     *
     * <p>예: {@code mood=HAPPY} (정의된 enum 아님) 는 Spring 의 Jackson 단에서
     * {@link InvalidFormatException} 으로 떨어진다. 사용자에게는 "mood: 허용되지 않는 값입니다"
     * 수준의 메시지가 도착한다. raw Jackson stacktrace 는 응답에 노출하지 않는다 (서버 내부 형태
     * 누설 회피).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleNotReadable(
            final HttpMessageNotReadableException httpMessageNotReadableException,
            final WebRequest webRequest) {
        final Throwable cause = httpMessageNotReadableException.getCause();
        final List<ApiErrorResponse.FieldErrorDetail> fieldErrors = new ArrayList<>();
        final String headline;
        if (cause instanceof InvalidFormatException invalidFormatException) {
            final String fieldName = resolveJacksonFieldName(invalidFormatException);
            final Object rejectedValue = maskIfSensitive(fieldName, invalidFormatException.getValue());
            final String message = "허용되지 않는 값입니다.";
            fieldErrors.add(new ApiErrorResponse.FieldErrorDetail(fieldName, message, rejectedValue));
            headline = fieldName + ": " + message;
        } else {
            headline = "요청 본문을 읽을 수 없습니다.";
        }
        log.warn("payload 파싱 실패 — headline={} causeType={}",
                headline, cause == null ? "null" : cause.getClass().getSimpleName());
        return buildResponse(HttpStatus.BAD_REQUEST, headline, webRequest, fieldErrors);
    }

    private static String resolveMessage(final FieldError fieldError) {
        final String defaultMessage = fieldError.getDefaultMessage();
        if (defaultMessage != null) {
            return defaultMessage;
        }
        // fallback — Spring 이 메시지 키만 들고 있는 경우.
        return fieldError.getCodes() == null || fieldError.getCodes().length == 0
                ? "검증 실패"
                : fieldError.getCodes()[0];
    }

    private static String resolveJacksonFieldName(final InvalidFormatException invalidFormatException) {
        if (invalidFormatException.getPath() == null || invalidFormatException.getPath().isEmpty()) {
            return "(unknown)";
        }
        return invalidFormatException.getPath().stream()
                .map(reference -> reference.getFieldName() == null
                        ? "[" + reference.getIndex() + "]"
                        : reference.getFieldName())
                .reduce((accumulator, segment) -> accumulator + "." + segment)
                .orElse("(unknown)");
    }

    /**
     * 첫 field error 사유 + "(외 N건)" 헤드라인. fieldErrors 가 비면 default 메시지.
     */
    private static String composeHeadline(final List<ApiErrorResponse.FieldErrorDetail> fieldErrors) {
        if (fieldErrors.isEmpty()) {
            return "요청이 유효하지 않습니다.";
        }
        final ApiErrorResponse.FieldErrorDetail first = fieldErrors.get(0);
        final String head = first.field() + ": " + first.message();
        if (fieldErrors.size() == 1) {
            return head;
        }
        return head + " (외 " + (fieldErrors.size() - 1) + "건)";
    }

    /**
     * sensitive field 면 rejectedValue 를 마스킹한다. null 은 그대로 (Bean Validation 메시지가
     * "must not be null" 처럼 자체적으로 사유를 담음).
     */
    static Object maskIfSensitive(final String fieldName, final Object rejectedValue) {
        if (rejectedValue == null) {
            return null;
        }
        if (SENSITIVE_FIELD_NAMES.contains(fieldName)) {
            return MASK;
        }
        return rejectedValue;
    }

    /**
     * {@code resolveMessage} 가 Spring 6 의 {@link DefaultMessageSourceResolvable} 메시지를
     * 사용하지 않더라도 default message 의 한국어 정리는 각 validation annotation 의
     * {@code message} 속성 또는 {@code messages.properties} 추가로 일관화한다 — 본 PR scope 밖.
     */
    private static ResponseEntity<ApiErrorResponse> buildResponse(
            final HttpStatus httpStatus,
            final String message,
            final WebRequest webRequest,
            final List<ApiErrorResponse.FieldErrorDetail> fieldErrors) {
        final String path = resolvePath(webRequest);
        final ApiErrorResponse body = new ApiErrorResponse(
                OffsetDateTime.now(),
                httpStatus.value(),
                httpStatus.getReasonPhrase(),
                message,
                path,
                fieldErrors);
        return ResponseEntity.status(httpStatus).body(body);
    }

    private static String resolvePath(final WebRequest webRequest) {
        // ServletWebRequest description: "uri=/path". prefix 제거.
        final String description = webRequest.getDescription(false);
        if (description == null) {
            return "";
        }
        return description.startsWith("uri=") ? description.substring("uri=".length()) : description;
    }
}
