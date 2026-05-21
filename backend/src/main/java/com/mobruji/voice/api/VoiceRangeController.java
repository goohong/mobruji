package com.mobruji.voice.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mobruji.voice.api.dto.VoiceRangeCreateRequest;
import com.mobruji.voice.api.dto.VoiceRangeResponse;
import com.mobruji.voice.api.dto.VoiceRangeUpdateRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.mobruji.voice.application.VoiceRangeService;
import com.mobruji.voice.domain.VoiceRange;

@RestController
@RequestMapping("/api/v1/voice-ranges")
@RequiredArgsConstructor
public class VoiceRangeController {

    private final VoiceRangeService voiceRangeService;

    @PostMapping
    public ResponseEntity<VoiceRangeResponse> create(
            @Valid @RequestBody final VoiceRangeCreateRequest voiceRangeCreateRequest) {
        final VoiceRange voiceRange = voiceRangeService.createOrReplace(voiceRangeCreateRequest.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED).body(VoiceRangeResponse.from(voiceRange));
    }

    @GetMapping("/{sessionId}")
    public VoiceRangeResponse read(@PathVariable final String sessionId) {
        return VoiceRangeResponse.from(voiceRangeService.readBySessionId(sessionId));
    }

    @PutMapping("/{sessionId}")
    public VoiceRangeResponse update(
            @PathVariable final String sessionId,
            @Valid @RequestBody final VoiceRangeUpdateRequest voiceRangeUpdateRequest) {
        return VoiceRangeResponse.from(
                voiceRangeService.updateBySessionId(sessionId, voiceRangeUpdateRequest.toCommand()));
    }
}
