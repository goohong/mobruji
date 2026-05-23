package com.mobruji.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.mobruji.user.domain.AnonymousSession;
import com.mobruji.user.domain.RevokedReason;
import com.mobruji.user.infrastructure.AnonymousSessionRepository;

/**
 * {@link SessionActivityTracker} 단위 테스트 — 5분 캐시 윈도우 / DB flush 정책
 * (PR 3, #924, spec §5-2 / §5-8).
 */
class SessionActivityTrackerTest {

    private static final String SESSION_ID = "session-A";
    private static final Instant BASE = Instant.parse("2026-05-23T12:00:00Z");

    private AnonymousSessionRepository anonymousSessionRepository;
    private MutableClock mutableClock;
    private SessionActivityTracker sessionActivityTracker;

    @BeforeEach
    void setUp() {
        anonymousSessionRepository = Mockito.mock(AnonymousSessionRepository.class);
        mutableClock = new MutableClock(BASE);
        final AnonymousSessionProperties properties = new AnonymousSessionProperties(
                AnonymousSessionProperties.DEFAULT_TTL_DAYS,
                null,
                null,
                Duration.ofMinutes(5));
        sessionActivityTracker = new SessionActivityTracker(
                anonymousSessionRepository, properties, mutableClock);
        // 기본: AnonymousSession 행 존재 (활성)
        given(anonymousSessionRepository.findById(SESSION_ID))
                .willReturn(Optional.of(AnonymousSession.create(SESSION_ID)));
    }

    @Test
    @DisplayName("최초 markActive — cache miss → DB flush 1회")
    void markActive_firstCall_flushesOnce() {
        sessionActivityTracker.markActive(SESSION_ID);

        verify(anonymousSessionRepository, times(1)).findById(SESSION_ID);
        verify(anonymousSessionRepository, times(1)).save(any(AnonymousSession.class));
        assertThat(sessionActivityTracker.cacheSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("5분 이내 재호출 — cache hit → DB skip (flush 1회만)")
    void markActive_within5min_skipsFlush() {
        sessionActivityTracker.markActive(SESSION_ID);

        // 4분 59초 경과
        mutableClock.advance(Duration.ofMinutes(4).plusSeconds(59));
        sessionActivityTracker.markActive(SESSION_ID);

        verify(anonymousSessionRepository, times(1)).findById(SESSION_ID);
        verify(anonymousSessionRepository, times(1)).save(any(AnonymousSession.class));
    }

    @Test
    @DisplayName("5분 정각 경과 — cache 만료 → 재 flush")
    void markActive_after5min_flushesAgain() {
        sessionActivityTracker.markActive(SESSION_ID);

        // 정확히 5분 경과 — flushInterval 도달이라 reflush.
        mutableClock.advance(Duration.ofMinutes(5));
        sessionActivityTracker.markActive(SESSION_ID);

        verify(anonymousSessionRepository, times(2)).save(any(AnonymousSession.class));
    }

    @Test
    @DisplayName("AnonymousSession 행 없음 — idempotent (예외 없이 skip, 캐시 마킹은 됨)")
    void markActive_noRow_idempotent() {
        given(anonymousSessionRepository.findById("missing")).willReturn(Optional.empty());

        sessionActivityTracker.markActive("missing");

        verify(anonymousSessionRepository, never()).save(any(AnonymousSession.class));
        assertThat(sessionActivityTracker.cacheSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("revoked 행 — touch 시 IllegalStateException 회피, save 호출 안 함")
    void markActive_revoked_skipsTouch() {
        final AnonymousSession revoked = AnonymousSession.create("revoked-A");
        revoked.revoke(RevokedReason.TTL);
        given(anonymousSessionRepository.findById("revoked-A")).willReturn(Optional.of(revoked));

        sessionActivityTracker.markActive("revoked-A");

        verify(anonymousSessionRepository, never()).save(any(AnonymousSession.class));
    }

    @Test
    @DisplayName("두 sessionId 독립 캐싱 — 한 쪽 flush 가 다른 쪽 영향 주지 않음")
    void markActive_independentSessions() {
        given(anonymousSessionRepository.findById("session-B"))
                .willReturn(Optional.of(AnonymousSession.create("session-B")));

        sessionActivityTracker.markActive(SESSION_ID);
        sessionActivityTracker.markActive("session-B");

        // 1분만 경과 — A 는 hit, B 는 hit (둘 다 5분 미만)
        mutableClock.advance(Duration.ofMinutes(1));
        sessionActivityTracker.markActive(SESSION_ID);
        sessionActivityTracker.markActive("session-B");

        // 각각 1회씩 flush — 총 2회.
        verify(anonymousSessionRepository, times(2)).save(any(AnonymousSession.class));
    }

    /**
     * 테스트 용 mutable clock — Instant 만 진전.
     */
    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(final Instant initial) {
            this.instant = initial;
        }

        void advance(final Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
