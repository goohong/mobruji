package com.mobruji.voice.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.mobruji.auth.SessionAuthGuard;
import com.mobruji.voice.api.dto.VoiceRangeHistoryResponse;
import com.mobruji.voice.api.dto.VoiceRangeSnapshotResponse;
import com.mobruji.voice.application.VoiceRangeService;

import lombok.RequiredArgsConstructor;

/**
 * 세션별 음역 측정 시계열(history) 조회 엔드포인트.
 *
 * <p>spec: docs/features/voice-range-progress.md §5-2 (PR C).
 *
 * <p>경로는 spec 그대로 `/api/v1/sessions/{sessionId}/voice-range-history`를 사용한다. 기존
 * `VoiceRangeController`(`/api/v1/voice-ranges`) 와 매핑 프리픽스가 달라 별도 컨트롤러로 분리.
 *
 * <p>인증: rev 16(#238) — {@code X-Session-Id} 헤더로 호출자 sessionId 를 받아 path 와 일치할 때만
 * 통과한다 ({@link SessionAuthGuard}). 누락/불일치 → 401.
 */
@RestController
@RequiredArgsConstructor
public class VoiceRangeHistoryController {

    private final VoiceRangeService voiceRangeService;
    private final SessionAuthGuard sessionAuthGuard;

    @GetMapping("/api/v1/sessions/{sessionId}/voice-range-history")
    public VoiceRangeHistoryResponse readHistory(
            @PathVariable final String sessionId,
            @RequestHeader(value = "X-Session-Id", required = false) final String presentedSessionId) {
        sessionAuthGuard.verify(sessionId, presentedSessionId);
        return new VoiceRangeHistoryResponse(
                voiceRangeService.readHistoryBySessionId(sessionId).stream()
                        .map(VoiceRangeSnapshotResponse::from)
                        .toList());
    }
}
