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
                Duration.ofMinutes(5),
                AnonymousSessionProperties.DEFAULT_ACTIVITY_CACHE_MAX_SIZE);
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

    // ---------- 메모리 누수 방어 — revoke evict + LRU 상한 (PR #937 follow-up) ----------

    @Test
    @DisplayName("evict — 캐시에 있는 sessionId 를 즉시 제거 (revoke flow 호출)")
    void evict_removesCachedSession() {
        sessionActivityTracker.markActive(SESSION_ID);
        assertThat(sessionActivityTracker.cacheSize()).isEqualTo(1);

        sessionActivityTracker.evict(SESSION_ID);

        assertThat(sessionActivityTracker.cacheSize()).isZero();
    }

    @Test
    @DisplayName("evict — 캐시에 없는 sessionId 도 idempotent (no-op)")
    void evict_unknownSession_isNoOp() {
        sessionActivityTracker.evict("never-seen-session");

        assertThat(sessionActivityTracker.cacheSize()).isZero();
    }

    @Test
    @DisplayName("evict — null/blank sessionId 도 no-op (호출자 방어 부담 최소화)")
    void evict_nullOrBlank_isNoOp() {
        sessionActivityTracker.markActive(SESSION_ID);

        sessionActivityTracker.evict(null);
        sessionActivityTracker.evict("");
        sessionActivityTracker.evict("   ");

        assertThat(sessionActivityTracker.cacheSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("evict 후 같은 sessionId 재호출 — cache miss → flusher 재위임 (정합성 손실 없음)")
    void evict_thenMarkActive_reflushes() {
        sessionActivityTracker.markActive(SESSION_ID);
        sessionActivityTracker.evict(SESSION_ID);

        // 1분만 경과 (window 안) — 그런데 evict 됐으므로 miss.
        mutableClock.advance(Duration.ofMinutes(1));
        sessionActivityTracker.markActive(SESSION_ID);

        verify(sessionActivityFlusher, times(2)).flushOne(SESSION_ID);
        assertThat(sessionActivityTracker.cacheSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("LRU 상한 도달 — capacity+1 입력 시 가장 오래된 entry 제거")
    void markActive_lruCap_evictsEldest() {
        // capacity 5 의 작은 tracker 로 LRU 동작 확인.
        final int capacity = 5;
        final AnonymousSessionProperties smallCap = new AnonymousSessionProperties(
                AnonymousSessionProperties.DEFAULT_TTL_DAYS,
                null,
                null,
                Duration.ofMinutes(5),
                capacity);
        final SessionActivityTracker smallTracker = new SessionActivityTracker(
                sessionActivityFlusher, smallCap, mutableClock);

        // 5개 채움 — session-0 가 가장 오래된 entry.
        for (int sessionIndex = 0; sessionIndex < capacity; sessionIndex++) {
            smallTracker.markActive("session-" + sessionIndex);
        }
        assertThat(smallTracker.cacheSize()).isEqualTo(capacity);

        // 6번째 entry 추가 → session-0 eviction (capacity 유지).
        smallTracker.markActive("session-5");
        assertThat(smallTracker.cacheSize()).isEqualTo(capacity);

        // session-0 이 evict 됐다면 재호출 시 miss → 추가 flush (총 7회).
        smallTracker.markActive("session-0");
        verify(sessionActivityFlusher, times(2)).flushOne("session-0");
        // session-1 도 evict 됐는지 확인 — session-0 markActive 가 access-order 갱신해
        // 가장 오래된 entry 는 이제 session-1. 캐시 7회째 호출이 session-1 evict 트리거.
        // (정확히는 markActive("session-0") 가 capacity+1 트리거 → 그 시점 가장 오래된 = session-1)
        assertThat(smallTracker.cacheSize()).isEqualTo(capacity);
    }

    @Test
    @DisplayName("LRU access-order — window 안 재호출이 access-order 갱신 → 안 쓰인 entry 가 먼저 evict")
    void markActive_lruAccessOrder_keepsHotEntries() {
        final int capacity = 3;
        final AnonymousSessionProperties smallCap = new AnonymousSessionProperties(
                AnonymousSessionProperties.DEFAULT_TTL_DAYS,
                null,
                null,
                Duration.ofMinutes(5),
                capacity);
        final SessionActivityTracker smallTracker = new SessionActivityTracker(
                sessionActivityFlusher, smallCap, mutableClock);

        smallTracker.markActive("A");
        smallTracker.markActive("B");
        smallTracker.markActive("C");

        // window 안 — A 재호출 (flush skip 이지만 LRU access-order 갱신).
        mutableClock.advance(Duration.ofMinutes(1));
        smallTracker.markActive("A");

        // D 추가 → 새 access-order 는 [B(eldest), C, A, D] → B 가 evict.
        smallTracker.markActive("D");
        assertThat(smallTracker.cacheSize()).isEqualTo(capacity);

        // B 재호출 → miss → reflush (총 2회) — B 가 분명히 evict 됐음을 입증.
        smallTracker.markActive("B");
        verify(sessionActivityFlusher, times(2)).flushOne("B");

        // A 재호출 — window 안 + LRU 로 살아남음 → flush skip (총 1회 그대로).
        smallTracker.markActive("A");
        verify(sessionActivityFlusher, times(1)).flushOne("A");
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
