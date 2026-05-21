package com.mobruji.voice;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mobruji.voice.dto.VoiceRangeCreateRequest;
import com.mobruji.voice.dto.VoiceRangeResponse;
import com.mobruji.voice.dto.VoiceRangeUpdateRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/voice-ranges")
@RequiredArgsConstructor
public class VoiceRangeController {

    private final VoiceRangeService voiceRangeService;

    @PostMapping
    public ResponseEntity<VoiceRangeResponse> create(
            @Valid @RequestBody final VoiceRangeCreateRequest voiceRangeCreateRequest) {
        final VoiceRangeResponse voiceRangeResponse = voiceRangeService.createOrReplace(voiceRangeCreateRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(voiceRangeResponse);
    }

    @GetMapping("/{sessionId}")
    public VoiceRangeResponse read(@PathVariable final String sessionId) {
        return voiceRangeService.readBySessionId(sessionId);
    }

    @PutMapping("/{sessionId}")
    public VoiceRangeResponse update(
            @PathVariable final String sessionId,
            @Valid @RequestBody final VoiceRangeUpdateRequest voiceRangeUpdateRequest) {
        return voiceRangeService.updateBySessionId(sessionId, voiceRangeUpdateRequest);
    }
}
