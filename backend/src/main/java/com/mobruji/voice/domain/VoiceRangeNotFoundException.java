package com.mobruji.voice.domain;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class VoiceRangeNotFoundException extends RuntimeException {

    public VoiceRangeNotFoundException(final String sessionId) {
        super("VoiceRange not found for sessionId: " + sessionId);
    }
}
