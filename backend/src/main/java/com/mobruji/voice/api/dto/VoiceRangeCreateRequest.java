package com.mobruji.voice.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.mobruji.user.domain.AnonymousSession;
import com.mobruji.user.domain.SessionIdPatterns;
import com.mobruji.voice.application.CreateVoiceRangeCommand;
import com.mobruji.voice.domain.MidiRange;
import com.mobruji.voice.domain.VoiceRangeSourceMethod;

/**
 * {@code POST /api/v1/voice-ranges} 요청 body.
 *
 * <p>{@code sessionId} 는 client 가 발급한 UUIDv4 (ADR-0011). {@code SessionRotateRequest} 와 동일하게
 * {@link SessionIdPatterns#UUID_V4} 형식 강제 — 임의 문자열 거부 (#948). k6 부하 시나리오 및 기존 테스트
 * fixture 가 비-UUIDv4 sessionId 를 사용했다면 본 PR 머지와 함께 UUIDv4 로 동시 갱신된다.
 */
public record VoiceRangeCreateRequest(
        @NotBlank @Size(max = AnonymousSession.SESSION_ID_MAX_LENGTH) @Pattern(
                regexp = SessionIdPatterns.UUID_V4, message = SessionIdPatterns.UUID_V4_MESSAGE
        ) String sessionId,
        @NotNull @Min(MidiRange.LOWEST_ALLOWED_MIDI) @Max(MidiRange.HIGHEST_ALLOWED_MIDI) Integer lowestNoteMidi,
        @NotNull @Min(MidiRange.LOWEST_ALLOWED_MIDI) @Max(MidiRange.HIGHEST_ALLOWED_MIDI) Integer highestNoteMidi,
        @NotNull VoiceRangeSourceMethod sourceMethod
) {

    public CreateVoiceRangeCommand toCommand() {
        return new CreateVoiceRangeCommand(sessionId, lowestNoteMidi, highestNoteMidi, sourceMethod);
    }
}
