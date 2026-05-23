package com.mobruji.voice.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mobruji.user.application.SessionAuthGuard;
import com.mobruji.voice.api.dto.VoiceRangeCreateRequest;
import com.mobruji.voice.api.dto.VoiceRangeResponse;
import com.mobruji.voice.api.dto.VoiceRangeUpdateRequest;
import com.mobruji.voice.application.VoiceRangeService;
import com.mobruji.voice.domain.VoiceRange;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 세션 음역(VoiceRange) CRUD 엔드포인트.
 *
 * <p>인증: ADR-0011 §28 — endpoint 가 path 또는 request body 의 {@code sessionId} 만으로
 * 특정 sessionId 의 데이터를 조회·생성·수정할 수 있으므로 session-bound 다. 모든 endpoint 는
 * {@link SessionAuthGuard} 로 검증한다.
 *
 * <ul>
 * <li>POST: body {@code sessionId} vs {@code X-Session-Id} 헤더 일치</li>
 * <li>GET/PUT: path {@code sessionId} vs {@code X-Session-Id} 헤더 일치</li>
 * </ul>
 *
 * <p>이슈 #868 후속 적용 (ADR-0011 위반 시정).
 */
@RestController
@RequestMapping("/api/v1/voice-ranges")
@RequiredArgsConstructor
public class VoiceRangeController {

    private final VoiceRangeService voiceRangeService;

    private final SessionAuthGuard sessionAuthGuard;

    @PostMapping
    public ResponseEntity<VoiceRangeResponse> create(
            @Valid @RequestBody final VoiceRangeCreateRequest voiceRangeCreateRequest,
            @RequestHeader(value = "X-Session-Id", required = false) final String presentedSessionId) {
        sessionAuthGuard.verify(voiceRangeCreateRequest.sessionId(), presentedSessionId);
        final VoiceRange voiceRange = voiceRangeService.createOrReplace(voiceRangeCreateRequest.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED).body(VoiceRangeResponse.from(voiceRange));
    }

    @GetMapping("/{sessionId}")
    public VoiceRangeResponse read(
            @PathVariable final String sessionId,
            @RequestHeader(value = "X-Session-Id", required = false) final String presentedSessionId) {
        sessionAuthGuard.verify(sessionId, presentedSessionId);
        return VoiceRangeResponse.from(voiceRangeService.readBySessionId(sessionId));
    }

    @PutMapping("/{sessionId}")
    public VoiceRangeResponse update(
            @PathVariable final String sessionId,
            @Valid @RequestBody final VoiceRangeUpdateRequest voiceRangeUpdateRequest,
            @RequestHeader(value = "X-Session-Id", required = false) final String presentedSessionId) {
        sessionAuthGuard.verify(sessionId, presentedSessionId);
        return VoiceRangeResponse.from(
                voiceRangeService.updateBySessionId(sessionId, voiceRangeUpdateRequest.toCommand()));
    }
}
