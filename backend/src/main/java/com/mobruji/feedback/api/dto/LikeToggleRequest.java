package com.mobruji.feedback.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.mobruji.user.domain.AnonymousSession;
import com.mobruji.user.domain.SessionIdPatterns;

/**
 * {@code POST /api/v1/feedback/likes/toggle} 요청 body.
 *
 * <p>{@code sessionId} 는 client 가 발급한 UUIDv4 (ADR-0011).
 * {@link SessionIdPatterns#UUID_V4} 형식 강제 — {@code SessionRotateRequest} /
 * {@code VoiceRangeCreateRequest} 와 동일한 검증 일관성 유지 (#948 후속).
 */
public record LikeToggleRequest(
        @NotBlank @Size(max = AnonymousSession.SESSION_ID_MAX_LENGTH) @Pattern(
                regexp = SessionIdPatterns.UUID_V4, message = SessionIdPatterns.UUID_V4_MESSAGE
        ) String sessionId,
        @NotNull @Positive Long songId
) {
}
