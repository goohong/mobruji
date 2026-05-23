package com.mobruji.user.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.mobruji.user.domain.AnonymousSession;
import com.mobruji.user.infrastructure.AnonymousSessionRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 세션 귀속(session-bound) endpoint 인증 가드.
 *
 * <p>spec rev 16(#238): {@code GET /api/v1/sessions/{id}/voice-range-history},
 * {@code GET /api/v1/sessions/{id}/recommendation-history} 등 path 의 {@code sessionId}
 * 가 호출자 본인의 세션인지 검증한다. Spring Security 정식 도입 전까지의 임시 게이트.
 *
 * <p>spec {@code docs/features/anonymous-session-lifecycle.md} §5-2 (PR 3, #924) 로 만료/revoke
 * 게이트 확장:
 *
 * <ol>
 * <li>HTTP 헤더 {@code X-Session-Id} 누락/blank → 401</li>
 * <li>path sessionId 와 헤더 상수시간 비교 → 불일치 401</li>
 * <li>{@link AnonymousSessionRepository#findById(Object)} 조회 결과
 * <ul>
 * <li>없음 → bootstrap 옵션 (a) 채택 — 통과 (첫 호출 자동 등록 정책,
 * §5-5-1, Q2). 등록은 후속 {@link SessionActivityTracker} 가 lazy 수행.</li>
 * <li>{@code revokedAt != null} → 401 (TTL/USER_ROTATE/ACCOUNT_MERGE 후 옛 sessionId 재사용 차단)</li>
 * <li>{@code lastSeenAt + ttlDays < now()} → 401 (sliding TTL 만료)</li>
 * <li>OK → {@link SessionActivityTracker#markActive(String)} 로 lastSeenAt 갱신 (5분 캐시 윈도우)</li>
 * </ul>
 * </li>
 * </ol>
 *
 * <p>sessionId 원문은 로그/예외 메시지/응답에 절대 포함하지 않는다
 * ({@code docs/ai-harness/04-security-policy.md} 익명 세션 룰).
 * timing attack 회피용으로 {@link MessageDigest#isEqual(byte[], byte[])} 의 상수시간 비교를
 * 사용한다 (admin gate #229 동일 패턴).
 */
@Slf4j
@Component
public class SessionAuthGuard {

    private static final String HEADER_NAME = "X-Session-Id";

    private final AnonymousSessionRepository anonymousSessionRepository;
    private final SessionActivityTracker sessionActivityTracker;
    private final Duration ttl;

    public SessionAuthGuard(
            final AnonymousSessionRepository anonymousSessionRepository,
            final SessionActivityTracker sessionActivityTracker,
            final AnonymousSessionProperties anonymousSessionProperties) {
        this.anonymousSessionRepository = anonymousSessionRepository;
        this.sessionActivityTracker = sessionActivityTracker;
        this.ttl = Duration.ofDays(anonymousSessionProperties.ttlDays());
    }

    public void verify(final String pathSessionId, final String presentedSessionId) {
        if (pathSessionId == null || pathSessionId.isBlank()) {
            // path 가 비어있는 경우는 매핑 단계에서 사실상 차단되지만 방어적으로 막는다.
            log.warn("session-bound endpoint 접근 거부 — path sessionId 누락");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing session id");
        }
        if (presentedSessionId == null || presentedSessionId.isBlank()) {
            log.warn("session-bound endpoint 접근 거부 — {} 헤더 누락", HEADER_NAME);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing session id");
        }
        final byte[] expected = pathSessionId.getBytes(StandardCharsets.UTF_8);
        final byte[] presented = presentedSessionId.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, presented)) {
            log.warn("session-bound endpoint 접근 거부 — path/{} 헤더 불일치", HEADER_NAME);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "session id mismatch");
        }

        // §5-2 만료/revoke 게이트 (PR 3, #924).
        // bootstrap 옵션 (a) — 행이 없으면 통과 (SessionRotationService 의 bootstrap-on-rotate 와 일관).
        final Optional<AnonymousSession> sessionOpt = anonymousSessionRepository.findById(pathSessionId);
        if (sessionOpt.isEmpty()) {
            return;
        }
        final AnonymousSession session = sessionOpt.get();
        if (session.isRevoked()) {
            log.warn("session-bound endpoint 접근 거부 — sessionId revoked");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "session revoked");
        }
        final LocalDateTime expiresAt = session.getLastSeenAt().plus(ttl);
        if (expiresAt.isBefore(LocalDateTime.now())) {
            log.warn("session-bound endpoint 접근 거부 — sessionId expired (sliding TTL)");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "session expired");
        }

        // 통과 — 5분 캐시 윈도우 활동 마킹 (SessionActivityTracker 가 flush 시점 결정).
        sessionActivityTracker.markActive(pathSessionId);
    }
}
