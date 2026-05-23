package com.mobruji.user.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mobruji.user.infrastructure.AnonymousSessionRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 익명 sessionId 의 마지막 활동 시각(in-memory) 캐시 + DB flush 정책.
 *
 * <p>spec: {@code docs/features/anonymous-session-lifecycle.md} §5-2 / §5-8 (PR 3, #924).
 *
 * <ul>
 * <li>{@link SessionAuthGuard#verify(String, String)} 가 검증 성공 직후 {@link #markActive(String)} 호출.</li>
 * <li>같은 sessionId 가 {@code activity-flush-interval} (default 5분) 이내 재요청하면
 * in-memory 캐시 hit — DB UPDATE skip (매 요청 write 부담 회피, spec §5-2).</li>
 * <li>캐시 miss (또는 5분 초과) → DB 의 {@code AnonymousSession.lastSeenAt} 을 갱신하고 캐시에 마킹.</li>
 * </ul>
 *
 * <p>스레드 안전성: {@link ConcurrentHashMap} 기반. 동시 markActive 가 같은 sessionId 에 들어와도
 * {@link ConcurrentHashMap#compute} 로 atomic 처리 — flush 가 동시에 두 번 발생하지 않도록 보장.
 *
 * <p>Caffeine 등 외부 캐시 의존성을 추가하지 않는 이유: 5분 간격 flush 정책 + sessionId 1건당
 * 메모리 footprint ~ 100 bytes (UUID + Instant) 라 ConcurrentHashMap 으로 충분. 운영 측정 후
 * 폭주 시 ADR 로 Caffeine 도입 재검토.
 *
 * <p>장기 누적 방지: {@link com.mobruji.user.application.AnonymousSessionTtlCleanup} 가 매일
 * inactive sessionId 를 revoke 처리할 때 본 캐시도 별도 청소 필요. 본 PR 는 1차 구현 — revoke 된
 * sessionId 가 캐시에 잔존해도 SessionAuthGuard 가 DB 조회로 차단하므로 보안 문제 없음.
 * 메모리 누수 방어는 후속 ticket.
 */
@Slf4j
@Component
public class SessionActivityTracker {

    private final AnonymousSessionRepository anonymousSessionRepository;
    private final Duration flushInterval;
    private final Clock clock;
    private final ConcurrentHashMap<String, Instant> lastFlushedAt = new ConcurrentHashMap<>();

    @Autowired
    public SessionActivityTracker(
            final AnonymousSessionRepository anonymousSessionRepository,
            final AnonymousSessionProperties anonymousSessionProperties) {
        this(anonymousSessionRepository, anonymousSessionProperties, Clock.systemDefaultZone());
    }

    /** 테스트 용 시계 주입 생성자 — 5분 윈도우 만료 시뮬레이션. */
    SessionActivityTracker(
            final AnonymousSessionRepository anonymousSessionRepository,
            final AnonymousSessionProperties anonymousSessionProperties,
            final Clock clock) {
        this.anonymousSessionRepository = anonymousSessionRepository;
        this.flushInterval = anonymousSessionProperties.activityFlushInterval();
        this.clock = clock;
    }

    /**
     * sessionId 의 활동을 캐시에 마킹한다. 캐시 hit ({@code now - lastFlushedAt < flushInterval})
     * 이면 no-op. miss 면 DB UPDATE + 캐시 갱신.
     *
     * <p>flush 가 별 트랜잭션({@code REQUIRES_NEW})에서 수행 — 호출자(가드) 흐름의 read-only 트랜잭션
     * 안에서 write 가 섞이지 않도록 분리.
     */
    public void markActive(final String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        final Instant now = Instant.now(clock);

        // compute 로 atomic 결정 — 동시 호출 시 한 번만 flush.
        lastFlushedAt.compute(sessionId, (key, previous) -> {
            if (previous != null && Duration.between(previous, now).compareTo(flushInterval) < 0) {
                // 캐시 hit — flush skip, previous 시각 유지.
                return previous;
            }
            try {
                flushOne(key);
            } catch (final RuntimeException e) {
                // flush 실패 시 캐시에 마킹하지 않아 다음 호출이 재시도 — sessionId 원문 미노출.
                log.warn("session-activity flush 실패 reason={}", e.getMessage());
                return previous;
            }
            return now;
        });
    }

    /**
     * 한 sessionId 의 {@code lastSeenAt} 을 현재 시각으로 갱신한다.
     *
     * <p>AnonymousSession 행이 없는 경우(가드 통과한 bootstrap 미실행 sessionId) idempotent 처리 —
     * 무시. bootstrap 정책 (옵션 a) 의 자동 등록은 별 ticket 에서 본격 도입.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void flushOne(final String sessionId) {
        anonymousSessionRepository.findById(sessionId).ifPresent(session -> {
            if (session.isRevoked()) {
                // revoked 행은 touch 하지 않는다 — IllegalStateException 회피.
                return;
            }
            session.touch();
            anonymousSessionRepository.save(session);
        });
    }

    /** 테스트 용 — 캐시 비우기. */
    void clearCache() {
        lastFlushedAt.clear();
    }

    /** 테스트 용 — 캐시 크기 확인. */
    int cacheSize() {
        return lastFlushedAt.size();
    }
}
