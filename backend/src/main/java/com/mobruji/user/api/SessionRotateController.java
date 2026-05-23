package com.mobruji.user.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mobruji.user.api.dto.SessionDataMode;
import com.mobruji.user.api.dto.SessionRotateRequest;
import com.mobruji.user.api.dto.SessionRotateResponse;
import com.mobruji.user.application.SessionAuthGuard;
import com.mobruji.user.application.SessionRotationService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 사용자 트리거 sessionId 회전 endpoint.
 *
 * <p>spec: {@code docs/features/anonymous-session-lifecycle.md} §5-4, ADR-0013 §D-2.
 *
 * <ul>
 * <li>{@code POST /api/v1/sessions/rotate}</li>
 * <li>인증: {@code X-Session-Id} 헤더 ↔ body {@code currentSessionId} 상수시간 일치
 * ({@link SessionAuthGuard}).</li>
 * <li>요청 dataMode = {@code ANONYMIZE} → 400 (v0.4 까지 미지원, spec §5-4).</li>
 * <li>응답: 새 sessionId.</li>
 * </ul>
 *
 * <p>fe 노출은 v0.4 spec (#243) 에서 결정 — v0.3 은 endpoint 만 노출 (ADR-0013 §D-2 단서).
 */
@RestController
@RequiredArgsConstructor
public class SessionRotateController {

    private final SessionRotationService sessionRotationService;
    private final SessionAuthGuard sessionAuthGuard;

    @PostMapping("/api/v1/sessions/rotate")
    public SessionRotateResponse rotate(
            @Valid @RequestBody final SessionRotateRequest sessionRotateRequest,
            @RequestHeader(value = "X-Session-Id", required = false) final String presentedSessionId) {
        sessionAuthGuard.verify(sessionRotateRequest.currentSessionId(), presentedSessionId);

        if (sessionRotateRequest.resolveDataMode() == SessionDataMode.ANONYMIZE) {
            // ADR-0013 §D-3: opt-in anonymize 는 v0.4 후속. v0.3 은 400 + 명시 메시지.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "dataMode ANONYMIZE is not supported in v0.3 — only DELETE is available");
        }

        final String newSessionId = sessionRotationService.rotate(sessionRotateRequest.currentSessionId());
        return new SessionRotateResponse(newSessionId);
    }
}
