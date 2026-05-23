package com.mobruji.user.application;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mobruji.user.domain.AnonymousSession;
import com.mobruji.user.domain.RevokedReason;
import com.mobruji.user.infrastructure.AnonymousSessionRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * TTL 만료 batch — 매일 1회 inactive sessionId 를 일괄 revoke + cascade-delete.
 *
 * <p>spec: {@code docs/features/anonymous-session-lifecycle.md} §5-3, ADR-0013 §D-1.
 *
 * <ul>
 * <li>cron: {@code mobruji.session.cleanup-cron} (default {@code 0 0 4 * * *}, Asia/Seoul).</li>
 * <li>cutoff = now - ttlDays (default 180).</li>
 * <li>한 batch 당 최대 {@code cleanup-max-per-run} (default 10,000) sessionId 처리.</li>
 * <li>sessionId 별 독립 트랜잭션 ({@link SessionDataCascadeDeleter#cascadeDelete(String)} 가
 * REQUIRES_NEW) — 락 범위 분리.</li>
 * <li>관측성: 만료 sessionId 1건당 {@code mobruji.session.expired{reason="ttl"}} +1
 * (ADR-0013 §D-5).</li>
 * </ul>
 *
 * <p>Discord 알림은 후속 PR 에서 도입 — spec §5-3 의 알림 4 규칙 추가 (observability-baseline.md §5-6).
 * 본 PR 는 카운터까지만.
 */
@Slf4j
@Component
public class AnonymousSessionTtlCleanup {

    static final String METRIC_EXPIRED = "mobruji.session.expired";

    private final AnonymousSessionRepository anonymousSessionRepository;
    private final SessionDataCascadeDeleter sessionDataCascadeDeleter;
    private final AnonymousSessionProperties anonymousSessionProperties;
    private final Counter ttlExpiredCounter;

    public AnonymousSessionTtlCleanup(
            final AnonymousSessionRepository anonymousSessionRepository,
            final SessionDataCascadeDeleter sessionDataCascadeDeleter,
            final AnonymousSessionProperties anonymousSessionProperties,
            final MeterRegistry meterRegistry) {
        this.anonymousSessionRepository = anonymousSessionRepository;
        this.sessionDataCascadeDeleter = sessionDataCascadeDeleter;
        this.anonymousSessionProperties = anonymousSessionProperties;
        this.ttlExpiredCounter = Counter.builder(METRIC_EXPIRED)
                .description("Anonymous session revoke count, labeled by reason")
                .tag("reason", RevokedReason.TTL.toMetricLabel())
                .register(meterRegistry);
    }

    @Scheduled(cron = "${mobruji.session.cleanup-cron:0 0 4 * * *}", zone = "Asia/Seoul")
    public void expireInactiveSessions() {
        final BatchSummary summary = runOnce();
        log.info(
                "anonymous-session ttl batch: expired_sessions={} deleted_rows={}",
                summary.expiredSessionCount(), summary.deletedRowCount());
    }

    /**
     * 본체 — 테스트 용이성을 위해 cron 진입과 분리.
     *
     * <p>주의: 본 메서드는 트랜잭션을 열지 않는다 — sessionId 단위 cascade 가 자체
     * {@code REQUIRES_NEW} 트랜잭션을 사용하고, AnonymousSession revoke 도 별 트랜잭션
     * ({@link #revokeOne(String)}) 으로 분리. batch 전체를 한 트랜잭션으로 묶지 않는다
     * (spec §3 비기능 "트랜잭션 안전성").
     */
    public BatchSummary runOnce() {
        final int ttlDays = anonymousSessionProperties.ttlDays();
        final int maxPerRun = anonymousSessionProperties.cleanupMaxPerRun();
        final LocalDateTime cutoff = LocalDateTime.now().minus(Duration.ofDays(ttlDays));

        final List<String> sessionIds = anonymousSessionRepository
                .findInactiveSessionIds(cutoff, Limit.of(maxPerRun));

        if (sessionIds.isEmpty()) {
            return new BatchSummary(0, 0L);
        }

        long totalDeletedRows = 0L;
        int expiredCount = 0;
        for (final String sessionId : sessionIds) {
            try {
                totalDeletedRows += sessionDataCascadeDeleter.cascadeDelete(sessionId);
                revokeOne(sessionId);
                ttlExpiredCounter.increment();
                expiredCount++;
            } catch (final RuntimeException e) {
                // 한 sessionId 의 실패가 batch 를 멈추지 않도록 격리. sessionId 원문 미노출.
                log.warn("anonymous-session ttl batch: skipped one due to error reason={}", e.getMessage());
            }
        }
        return new BatchSummary(expiredCount, totalDeletedRows);
    }

    /**
     * AnonymousSession 행에 {@code revokedAt = now() + revokedReason = TTL} 마킹.
     *
     * <p>행이 없는 경우(이론상 findInactiveSessionIds 결과인데 동시 회전으로 사라진 경우) idempotent
     * 처리 — 무시.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeOne(final String sessionId) {
        anonymousSessionRepository.findById(sessionId).ifPresent(session -> {
            session.revoke(RevokedReason.TTL);
            anonymousSessionRepository.save(session);
        });
    }

    /**
     * batch 1회 실행 요약 — 테스트/관측성 용도.
     *
     * @param expiredSessionCount 본 batch 가 revoke 처리한 sessionId 수
     * @param deletedRowCount     cascade-delete 로 제거된 모든 테이블 row 수 합
     */
    public record BatchSummary(
            int expiredSessionCount,
            long deletedRowCount
    ) {

        /** {@link AnonymousSession} 의 행 자체는 포함하지 않는다 — 호출자가 별도 revoke. */
        public boolean isEmpty() {
            return expiredSessionCount == 0;
        }
    }
}
