package com.mobruji.user.application;

import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mobruji.user.infrastructure.AnonymousSessionRepository;

/**
 * {@link SessionActivityTracker} 가 호출하는 별 트랜잭션 flush 컴포넌트.
 *
 * <p>spec: {@code docs/features/anonymous-session-lifecycle.md} §5-2 (PR 3, #924, rev follow-up #936).
 *
 * <p><b>왜 별 클래스로 분리했나.</b> {@link SessionActivityTracker#markActive(String)} 가 같은
 * 클래스의 {@code flushOne} 을 직접 호출하면 Spring AOP proxy 를 우회해서
 * {@code @Transactional(REQUIRES_NEW)} 가 무효화된다 (self-invocation 문제). 외부 bean (본
 * 컴포넌트) 호출로 강제해 proxy 가 실제로 트랜잭션을 별도로 시작하도록 보장한다.
 *
 * <p>옵션 검토 — (A) 별 컴포넌트 분리 [채택], (B) {@code @TransactionalEventListener},
 * (C) self-injection. (A) 가 가장 명시적 + 테스트 격리 쉬워서 채택. 메모리/코드 복잡도 최소.
 */
@Component
public class SessionActivityFlusher {

    private final AnonymousSessionRepository anonymousSessionRepository;

    @Autowired
    public SessionActivityFlusher(final AnonymousSessionRepository anonymousSessionRepository) {
        this.anonymousSessionRepository = anonymousSessionRepository;
    }

    /**
     * 한 sessionId 의 {@code lastSeenAt} 을 현재 시각으로 갱신한다.
     *
     * <p>{@code REQUIRES_NEW} 로 별 트랜잭션 — 호출자 (가드) 흐름의 read-only 트랜잭션 안에서
     * write 가 섞이지 않도록 분리.
     *
     * <p>AnonymousSession 행이 없는 경우 (가드 통과한 bootstrap 미실행 sessionId) idempotent 처리 —
     * 무시. revoked 행은 touch 시 IllegalStateException 회피용으로 skip.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void flushOne(final String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        anonymousSessionRepository.findById(sessionId).ifPresent(session -> {
            if (session.isRevoked()) {
                return;
            }
            session.touch();
            anonymousSessionRepository.save(session);
        });
    }
}
