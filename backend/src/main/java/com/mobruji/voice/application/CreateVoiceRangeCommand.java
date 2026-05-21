package com.mobruji.voice.application;

import java.util.Objects;

import com.mobruji.voice.domain.VoiceRangeSourceMethod;

/**
 * VoiceRange 생성/대체 유스케이스의 입력 커맨드. api.dto의 검증 어노테이션을 거치지 않고
 * application 계층에 전달되는 순수 입력 모델 (ADR 0005 §A-7 — application은 api에 의존하지 않는다).
 */
public record CreateVoiceRangeCommand(
        String sessionId,
        int lowestNoteMidi,
        int highestNoteMidi,
        VoiceRangeSourceMethod sourceMethod
) {

    public CreateVoiceRangeCommand {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(sourceMethod, "sourceMethod must not be null");
    }
}
