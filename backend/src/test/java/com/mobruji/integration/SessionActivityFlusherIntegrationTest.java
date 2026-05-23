package com.mobruji.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.mobruji.user.application.SessionActivityFlusher;
import com.mobruji.user.domain.AnonymousSession;
import com.mobruji.user.domain.RevokedReason;
import com.mobruji.user.infrastructure.AnonymousSessionRepository;

/**
 * {@link SessionActivityFlusher} 통합 테스트 — REQUIRES_NEW 트랜잭션 실제 분리 검증
 * (rev follow-up #936, spec §5-2).
 *
 * <p>self-invocation 해소 후 별 컴포넌트로 분리됐기 때문에 Spring AOP proxy 가 정상 동작해 외부
 * 트랜잭션이 rollback 되어도 flusher 의 lastSeenAt 갱신은 commit 된다는 점을 검증한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class SessionActivityFlusherIntegrationTest {

    @Autowired
    private SessionActivityFlusher sessionActivityFlusher;

    @Autowired
    private AnonymousSessionRepository anonymousSessionRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        anonymousSessionRepository.deleteAll();
    }

    @Test
    @DisplayName("flushOne: 존재하는 sessionId 의 lastSeenAt 을 현재 시각으로 갱신")
    void flushOne_updatesLastSeenAt() {
        // given: lastSeenAt 이 과거인 행
        final LocalDateTime past = LocalDateTime.now().minusHours(2);
        persistWithLastSeen("session-X", past);

        // when
        sessionActivityFlusher.flushOne("session-X");

        // then: lastSeenAt 이 갱신됨 (과거보다 미래)
        final AnonymousSession reloaded = anonymousSessionRepository.findById("session-X").orElseThrow();
        assertThat(reloaded.getLastSeenAt()).isAfter(past);
    }

    @Test
    @DisplayName("flushOne: AnonymousSession 행 없음 — idempotent (예외 없이 skip)")
    void flushOne_noRow_idempotent() {
        // when / then: 예외 없이 통과
        sessionActivityFlusher.flushOne("nonexistent-session");

        assertThat(anonymousSessionRepository.findById("nonexistent-session")).isEmpty();
    }

    @Test
    @DisplayName("flushOne: revoked 행 — touch 시 IllegalStateException 회피, lastSeenAt 미변경")
    void flushOne_revoked_skipsTouch() {
        // given: revoked 행 (lastSeenAt 을 과거로 박아 flush 가 동작했다면 즉시 감지 가능)
        final LocalDateTime past = LocalDateTime.now().minusHours(5);
        persistWithLastSeen("revoked-X", past);
        final AnonymousSession persisted = anonymousSessionRepository.findById("revoked-X").orElseThrow();
        persisted.revoke(RevokedReason.TTL);
        anonymousSessionRepository.save(persisted);

        // when
        sessionActivityFlusher.flushOne("revoked-X");

        // then: lastSeenAt 이 과거 시점 그대로 (touch skip 됨 — flush 발생했다면 now 로 갱신됐을 것)
        // 정확한 시각 비교 대신 "flush 가 발생했다면 now 근처일 것" 로 음성 검증 — 5시간 전 그대로 유지면 OK.
        final AnonymousSession reloaded = anonymousSessionRepository.findById("revoked-X").orElseThrow();
        assertThat(reloaded.getLastSeenAt())
                .isBefore(LocalDateTime.now().minusHours(1))
                .isAfter(LocalDateTime.now().minusHours(10));
    }

    @Test
    @DisplayName("REQUIRES_NEW 분리: 외부 트랜잭션 rollback 되어도 flushOne commit 은 유지")
    void flushOne_requiresNew_independentFromOuterRollback() {
        // given
        final LocalDateTime past = LocalDateTime.now().minusHours(3);
        persistWithLastSeen("session-Y", past);

        // when: 외부 트랜잭션 안에서 flushOne 호출 후 의도적 예외로 외부 rollback
        try {
            transactionTemplate.execute(status -> {
                sessionActivityFlusher.flushOne("session-Y");
                throw new IllegalStateException("intentional rollback");
            });
        } catch (final IllegalStateException expected) {
            // expected
        }

        // then: flushOne 은 별 트랜잭션이라 lastSeenAt 갱신 commit 됨
        final AnonymousSession reloaded = anonymousSessionRepository.findById("session-Y").orElseThrow();
        assertThat(reloaded.getLastSeenAt()).isAfter(past);
    }

    /** lastSeenAt 을 임의 시점으로 박은 행 영속 — AnonymousSessionTtlCleanupIntegrationTest 동일 패턴. */
    private void persistWithLastSeen(final String sessionId, final LocalDateTime lastSeenAt) {
        final AnonymousSession anonymousSession = AnonymousSession.create(sessionId);
        try {
            final Field lastSeenAtField = AnonymousSession.class.getDeclaredField("lastSeenAt");
            lastSeenAtField.setAccessible(true);
            lastSeenAtField.set(anonymousSession, lastSeenAt);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("test setup failed", e);
        }
        anonymousSessionRepository.save(anonymousSession);
    }
}
