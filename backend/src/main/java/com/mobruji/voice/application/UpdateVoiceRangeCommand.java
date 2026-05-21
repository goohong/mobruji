package com.mobruji.voice.application;

import java.util.Objects;

import com.mobruji.voice.domain.VoiceRangeSourceMethod;

/**
 * VoiceRange 업데이트 유스케이스의 입력 커맨드. api.dto 의존을 끊기 위한 application 계층 내부 입력 모델.
 */
public record UpdateVoiceRangeCommand(
        int lowestNoteMidi,
        int highestNoteMidi,
        VoiceRangeSourceMethod sourceMethod
) {

    public UpdateVoiceRangeCommand {
        Objects.requireNonNull(sourceMethod, "sourceMethod must not be null");
    }
}
