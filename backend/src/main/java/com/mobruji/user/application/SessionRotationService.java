package com.mobruji.user.application;

import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mobruji.user.domain.AnonymousSession;
import com.mobruji.user.domain.RevokedReason;
import com.mobruji.user.infrastructure.AnonymousSessionRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 사용자 트리거 sessionId 회전 서비스.
 *
 * <p>spec: {@code docs/features/anonymous-session-lifecycle.md} §5-4, ADR-0013 §D-2.
 *
 * <ul>
 * <li>입력: 현재 sessionId ({@code SessionAuthGuard} 가 헤더-path 일치 1차 검증).</li>
 * <li>처리: 기존 sessionId 의 데이터 cascade-delete + AnonymousSession 행 revoke (USER_ROTATE) +
 * 새 UUIDv4 sessionId 발급 후 AnonymousSession 행 생성.</li>
 * <li>출력: 새 sessionId.</li>
 * </ul>
 *
 * <p>관측성: 회전 1회당 {@code mobruji.session.rotated} +1, 추가로
 * {@code mobruji.session.expired{reason="user_rotate"}} +1 (ADR-0013 §D-5 라벨 단일 진실 —
 * "expired" 카운터의 reason 라벨에 회전 케이스도 포함).
 */
@Slf4j
@Service
public class SessionRotationService {

    static final String METRIC_ROTATED = "mobruji.session.rotated";
    static final String METRIC_EXPIRED = "mobruji.session.expired";

    private final AnonymousSessionRepository anonymousSessionRepository;
    private final SessionDataCascadeDeleter sessionDataCascadeDeleter;
    private final Counter rotatedCounter;
    private final Counter userRotateExpiredCounter;

    public SessionRotationService(
            final AnonymousSessionRepository anonymousSessionRepository,
            final SessionDataCascadeDeleter sessionDataCascadeDeleter,
            final MeterRegistry meterRegistry) {
        this.anonymousSessionRepository = anonymousSessionRepository;
        this.sessionDataCascadeDeleter = sessionDataCascadeDeleter;
        this.rotatedCounter = Counter.builder(METRIC_ROTATED)
                .description("Anonymous session rotation count (user-triggered)")
                .register(meterRegistry);
        this.userRotateExpiredCounter = Counter.builder(METRIC_EXPIRED)
                .description("Anonymous session revoke count, labeled by reason")
                .tag("reason", RevokedReason.USER_ROTATE.toMetricLabel())
                .register(meterRegistry);
    }

    /**
     * 회전 수행 후 새 sessionId 를 반환한다.
     *
     * <p>트랜잭션 경계: 본 메서드 전체가 단일 @Transactional. 단,
     * {@link SessionDataCascadeDeleter#cascadeDelete(String)} 는 {@code REQUIRES_NEW} 로
     * 분리되어 cascade-delete 가 별 트랜잭션에서 커밋된다 (락 범위 분리, spec §3 비기능).
     *
     * @param currentSessionId 회전 전 sessionId. AnonymousSession 행이 없어도 OK (bootstrap-on-rotate).
     * @return 새로 발급한 sessionId (UUIDv4)
     */
    @Transactional
    public String rotate(final String currentSessionId) {
        Objects.requireNonNull(currentSessionId, "currentSessionId must not be null");
        if (currentSessionId.isBlank()) {
            throw new IllegalArgumentException("currentSessionId must not be blank");
        }

        // 기존 sessionId 데이터 cascade-delete (별 트랜잭션)
        sessionDataCascadeDeleter.cascadeDelete(currentSessionId);

        // AnonymousSession 행 revoke (없으면 생성 후 revoke — bootstrap-on-rotate).
        // bootstrap 정책 spec §5-5-1 Q2 의 (a) 옵션 — 본 PR 는 회전 진입 시점만 자동 등록.
        // SessionAuthGuard 의 만료 게이트 통합(spec §5-2 / PR 3) 은 후속.
        final AnonymousSession current = anonymousSessionRepository.findById(currentSessionId)
                .orElseGet(() -> AnonymousSession.create(currentSessionId));
        current.revoke(RevokedReason.USER_ROTATE);
        anonymousSessionRepository.save(current);

        // 새 sessionId 발급 + AnonymousSession 행 생성
        final String newSessionId = UUID.randomUUID().toString();
        anonymousSessionRepository.save(AnonymousSession.create(newSessionId));

        rotatedCounter.increment();
        userRotateExpiredCounter.increment();
        log.info("anonymous-session rotated");
        return newSessionId;
    }
}
