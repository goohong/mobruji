package com.mobruji.recommendation.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.mobruji.recommendation.domain.FeedbackReaction;
import com.mobruji.user.domain.AnonymousSession;
import com.mobruji.user.domain.SessionIdPatterns;

/**
 * {@code POST /api/v1/sessions/{sessionId}/feedback} 요청 body(#1545 §5-2).
 *
 * <p>{@code sessionId} 는 client 가 발급한 UUIDv4(ADR-0011) — path 와 일치해야 하며 {@code SessionAuthGuard}
 * 가 {@code X-Session-Id} 헤더와 함께 검증한다. {@code reaction} 은 {@code LIKE}/{@code PASS}.
 */
public record FeedbackRecordRequest(
        @NotBlank @Size(max = AnonymousSession.SESSION_ID_MAX_LENGTH) @Pattern(
                regexp = SessionIdPatterns.UUID_V4, message = SessionIdPatterns.UUID_V4_MESSAGE
        ) String sessionId,
        @NotNull @Positive Long songId,
        @NotNull FeedbackReaction reaction
) {
}
