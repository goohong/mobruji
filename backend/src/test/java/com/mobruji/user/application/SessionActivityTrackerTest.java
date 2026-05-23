package com.mobruji.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * {@link SessionActivityTracker} 단위 테스트 — 5분 캐시 윈도우 / flusher 위임 정책
 * (PR 3, #924, spec §5-2 / §5-8, rev follow-up #936).
 */
class SessionActivityTrackerTest {

    private static final String SESSION_ID = "session-A";
    private static final Instant BASE = Instant.parse("2026-05-23T12:00:00Z");

    private SessionActivityFlusher sessionActivityFlusher;
    private MutableClock mutableClock;
    private SessionActivityTracker sessionActivityTracker;

    @BeforeEach
    void setUp() {
        sessionActivityFlusher = Mockito.mock(SessionActivityFlusher.class);
        mutableClock = new MutableClock(BASE);
        final AnonymousSessionProperties properties = new AnonymousSessionProperties(
                AnonymousSessionProperties.DEFAULT_TTL_DAYS,
                null,
                null,
                Duration.ofMinutes(5));
        sessionActivityTracker = new SessionActivityTracker(
                sessionActivityFlusher, properties, mutableClock);
    }

    @Test
    @DisplayName("최초 markActive — cache miss → flusher.flushOne 1회 위임")
    void markActive_firstCall_flushesOnce() {
        sessionActivityTracker.markActive(SESSION_ID);

        verify(sessionActivityFlusher, times(1)).flushOne(SESSION_ID);
        assertThat(sessionActivityTracker.cacheSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("5분 이내 재호출 — cache hit → flusher 위임 skip (1회만)")
    void markActive_within5min_skipsFlush() {
        sessionActivityTracker.markActive(SESSION_ID);

        // 4분 59초 경과
        mutableClock.advance(Duration.ofMinutes(4).plusSeconds(59));
        sessionActivityTracker.markActive(SESSION_ID);

        verify(sessionActivityFlusher, times(1)).flushOne(SESSION_ID);
    }

    @Test
    @DisplayName("5분 정각 경과 — cache 만료 → 재 flush 위임")
    void markActive_after5min_flushesAgain() {
        sessionActivityTracker.markActive(SESSION_ID);

        // 정확히 5분 경과 — flushInterval 도달이라 reflush.
        mutableClock.advance(Duration.ofMinutes(5));
        sessionActivityTracker.markActive(SESSION_ID);

        verify(sessionActivityFlusher, times(2)).flushOne(SESSION_ID);
    }

    @Test
    @DisplayName("두 sessionId 독립 캐싱 — 한 쪽 flush 가 다른 쪽 영향 주지 않음")
    void markActive_independentSessions() {
        sessionActivityTracker.markActive(SESSION_ID);
        sessionActivityTracker.markActive("session-B");

        // 1분만 경과 — A 는 hit, B 는 hit (둘 다 5분 미만)
        mutableClock.advance(Duration.ofMinutes(1));
        sessionActivityTracker.markActive(SESSION_ID);
        sessionActivityTracker.markActive("session-B");

        // 각각 1회씩 flusher 위임 — 총 2회.
        verify(sessionActivityFlusher, times(1)).flushOne(SESSION_ID);
        verify(sessionActivityFlusher, times(1)).flushOne("session-B");
    }

    @Test
    @DisplayName("flusher 가 RuntimeException 던지면 캐시에 마킹 안 함 — 다음 호출이 재시도")
    void markActive_flusherFails_retriesOnNextCall() {
        willThrow(new DataAccessResourceFailureException("DB down"))
                .given(sessionActivityFlusher).flushOne(SESSION_ID);

        sessionActivityTracker.markActive(SESSION_ID);

        // 첫 호출 실패 — 캐시 비어있음.
        assertThat(sessionActivityTracker.cacheSize()).isZero();

        // 2번째 호출도 즉시 flusher 위임 (windows hit 가 아니므로 재시도).
        sessionActivityTracker.markActive(SESSION_ID);
        verify(sessionActivityFlusher, times(2)).flushOne(SESSION_ID);
    }

    @Test
    @DisplayName("flusher 성공 후 캐시에 마킹 — 후속 호출은 window 안에서 skip")
    void markActive_successThenCached() {
        // flusher 기본 동작 — void 메서드 mock 은 자동 no-op.
        sessionActivityTracker.markActive(SESSION_ID);

        mutableClock.advance(Duration.ofMinutes(1));
        sessionActivityTracker.markActive(SESSION_ID);

        verify(sessionActivityFlusher, times(1)).flushOne(SESSION_ID);
        assertThat(sessionActivityTracker.cacheSize()).isEqualTo(1);
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
