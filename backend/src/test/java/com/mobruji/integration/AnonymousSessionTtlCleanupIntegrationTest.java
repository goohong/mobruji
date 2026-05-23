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

import com.mobruji.user.application.AnonymousSessionTtlCleanup;
import com.mobruji.user.application.SessionActivityTracker;
import com.mobruji.user.domain.AnonymousSession;
import com.mobruji.user.domain.RevokedReason;
import com.mobruji.user.infrastructure.AnonymousSessionRepository;

/**
 * TTL 만료 batch 통합 테스트.
 *
 * <p>spec: {@code docs/features/anonymous-session-lifecycle.md} §5-3 / §7, ADR-0013 §D-1.
 */
@SpringBootTest
@ActiveProfiles("test")
class AnonymousSessionTtlCleanupIntegrationTest {

    @Autowired
    private AnonymousSessionTtlCleanup anonymousSessionTtlCleanup;

    @Autowired
    private AnonymousSessionRepository anonymousSessionRepository;

    @Autowired
    private SessionActivityTracker sessionActivityTracker;

    @BeforeEach
    void setUp() {
        anonymousSessionRepository.deleteAll();
        sessionActivityTracker.clearCache();
    }

    @Test
    @DisplayName("runOnce: 만료된 sessionId 는 revoked(TTL), 활성 sessionId 는 보존된다")
    void runOnce_expiresOnlyInactiveSessions() {
        // given: 활성 1건 (lastSeenAt = now), 만료 2건 (lastSeenAt = 200일 전)
        anonymousSessionRepository.save(AnonymousSession.create("active-session"));
        persistWithOldLastSeen("expired-1", LocalDateTime.now().minusDays(200));
        persistWithOldLastSeen("expired-2", LocalDateTime.now().minusDays(200));

        // when
        final AnonymousSessionTtlCleanup.BatchSummary summary = anonymousSessionTtlCleanup.runOnce();

        // then: 2건 만료 (각각 row 삭제 0 — 대상 데이터 없음)
        assertThat(summary.expiredSessionCount()).isEqualTo(2);
        assertThat(anonymousSessionRepository.findById("active-session").orElseThrow().isRevoked()).isFalse();
        assertThat(anonymousSessionRepository.findById("expired-1").orElseThrow().getRevokedReason())
                .isEqualTo(RevokedReason.TTL);
        assertThat(anonymousSessionRepository.findById("expired-2").orElseThrow().getRevokedReason())
                .isEqualTo(RevokedReason.TTL);
    }

    @Test
    @DisplayName("runOnce: 만료 대상이 없으면 0/0 반환")
    void runOnce_noTargets_returnsZero() {
        anonymousSessionRepository.save(AnonymousSession.create("active-only"));

        final AnonymousSessionTtlCleanup.BatchSummary summary = anonymousSessionTtlCleanup.runOnce();

        assertThat(summary.expiredSessionCount()).isZero();
        assertThat(summary.deletedRowCount()).isZero();
        assertThat(summary.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("runOnce: 만료된 sessionId 의 in-memory activity cache 도 evict (메모리 누수 방어, PR #937 follow-up)")
    void runOnce_evictsActivityCacheForExpiredSessions() {
        // given: 만료 1건 — cache 에 미리 markActive 로 채워 둠.
        persistWithOldLastSeen("expired-cache-1", LocalDateTime.now().minusDays(200));
        // markActive 가 flushOne → touch() 로 lastSeenAt = now() 로 갱신해 cutoff 회피하므로,
        // markActive 후 다시 lastSeenAt 을 과거로 박아 만료 대상 자격을 복원한다 (테스트 한정 시나리오).
        sessionActivityTracker.markActive("expired-cache-1");
        assertThat(sessionActivityTracker.cacheSize()).isEqualTo(1);
        rewindLastSeen("expired-cache-1", LocalDateTime.now().minusDays(200));

        // when
        final AnonymousSessionTtlCleanup.BatchSummary summary = anonymousSessionTtlCleanup.runOnce();

        // then: revoke + cache evict.
        assertThat(summary.expiredSessionCount()).isEqualTo(1);
        assertThat(sessionActivityTracker.cacheSize()).isZero();
    }

    @Test
    @DisplayName("runOnce: 이미 revoked 된 sessionId 는 대상에서 제외")
    void runOnce_skipsAlreadyRevoked() {
        // given: 만료 시각이지만 이미 revoked
        final AnonymousSession alreadyRevoked = persistWithOldLastSeen(
                "already-revoked", LocalDateTime.now().minusDays(300));
        alreadyRevoked.revoke(RevokedReason.USER_ROTATE);
        anonymousSessionRepository.save(alreadyRevoked);

        // when
        final AnonymousSessionTtlCleanup.BatchSummary summary = anonymousSessionTtlCleanup.runOnce();

        // then: revoke reason 이 USER_ROTATE 그대로 (TTL 로 덮어쓰지 않음)
        assertThat(summary.expiredSessionCount()).isZero();
        assertThat(anonymousSessionRepository.findById("already-revoked").orElseThrow().getRevokedReason())
                .isEqualTo(RevokedReason.USER_ROTATE);
    }

    /**
     * lastSeenAt 을 과거 시점으로 직접 박은 행 영속.
     *
     * <p>{@link AnonymousSession#create(String)} 는 now() 로 박으므로, 만료 시나리오 영속을 위해
     * reflection 으로 lastSeenAt 을 override. 테스트 한정.
     */
    private AnonymousSession persistWithOldLastSeen(final String sessionId, final LocalDateTime lastSeenAt) {
        final AnonymousSession anonymousSession = AnonymousSession.create(sessionId);
        try {
            final Field lastSeenAtField = AnonymousSession.class.getDeclaredField("lastSeenAt");
            lastSeenAtField.setAccessible(true);
            lastSeenAtField.set(anonymousSession, lastSeenAt);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("test setup failed", e);
        }
        return anonymousSessionRepository.save(anonymousSession);
    }

    /**
     * 이미 영속된 행의 lastSeenAt 을 과거로 되돌린다 — markActive 의 touch() 부작용 회피용.
     */
    private void rewindLastSeen(final String sessionId, final LocalDateTime lastSeenAt) {
        final AnonymousSession anonymousSession = anonymousSessionRepository.findById(sessionId).orElseThrow();
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
