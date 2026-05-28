package com.mobruji.user.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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
 * <li>캐시 miss (또는 5분 초과) → {@link SessionActivityFlusher#flushOne(String)} 로 DB UPDATE
 * 위임 후 캐시에 마킹.</li>
 * </ul>
 *
 * <p>스레드 안전성: access-order {@link LinkedHashMap} 을
 * {@link Collections#synchronizedMap(Map)} 으로 감싼다. 모든 read/write 가 같은 monitor 위에서
 * 직렬화되므로 동시 {@link #markActive(String)} 가 같은 sessionId 에 대해 두 번 flush 하지 않는다
 * (LRU access-order 갱신과 hit 판정이 한 atomic block 안에 있음).
 *
 * <p>Caffeine 등 외부 캐시 의존성을 추가하지 않는 이유: 메모리 안전(LRU 상한 + revoke 시점
 * 직접 evict)을 JDK 만으로 달성 가능하고, build.gradle 보호 영역 변경 부담을 피하기 위함이다
 * (PR #937 follow-up, 2026-05-24). 운영 측정 후 capacity 부족이 잦아지면 ADR 로 Caffeine
 * 도입 재검토.
 *
 * <p><b>메모리 누수 방어 (PR #937 follow-up).</b> 2 layer 방어:
 * <ol>
 * <li><b>revoke 시점 직접 evict</b> — {@link SessionRotationService#rotate(String)} 와
 * {@link AnonymousSessionTtlCleanup#revokeOne(String)} 가 본 클래스의 {@link #evict(String)}
 * 를 호출해 정상 라이프사이클 종료 sessionId 를 즉시 캐시에서 제거한다.</li>
 * <li><b>LRU 상한 fallback</b> — 위조/누락된 sessionId 가 revoke 경로를 거치지 않고 누적될
 * 가능성에 대비해 {@code activity-cache-max-size} (default 10,000) 도달 시 access-order LRU
 * 로 가장 오래된 entry 를 제거한다. 캐시에서 빠진 sessionId 가 다음에 다시 요청해도
 * miss → flush → 재마킹으로 정상 동작 (정합성 손실 없음).</li>
 * </ol>
 * SessionAuthGuard 가 revoke 된 sessionId 를 DB 조회로 401 차단하므로, 캐시 잔존이 보안
 * 문제로 직결되진 않는다 — 본 layer 는 OOM/heap 누수 방어 전용이다.
 *
 * <p><b>self-invocation 회피.</b> flush 는 {@link SessionActivityFlusher} (별 컴포넌트) 가 담당해
 * Spring AOP proxy 를 통한 {@code @Transactional(REQUIRES_NEW)} 가 실효되도록 분리한다
 * (rev follow-up #936). 본 클래스에서 같은 클래스의 flush 메서드를 직접 호출하면 proxy 우회로
 * 트랜잭션이 부모 트랜잭션에 흡수돼 read-only 가드 흐름과 write 가 섞이는 문제 발생.
 */
@Slf4j
@Component
public class SessionActivityTracker {

    private final SessionActivityFlusher sessionActivityFlusher;
    private final Duration flushInterval;
    private final Clock clock;
    private final Map<String, Instant> lastFlushedAt;

    @Autowired
    public SessionActivityTracker(
            final SessionActivityFlusher sessionActivityFlusher,
            final AnonymousSessionProperties anonymousSessionProperties) {
        this(sessionActivityFlusher, anonymousSessionProperties, Clock.systemDefaultZone());
    }

    /** 테스트 용 시계 주입 생성자 — 5분 윈도우 만료 시뮬레이션. */
    SessionActivityTracker(
            final SessionActivityFlusher sessionActivityFlusher,
            final AnonymousSessionProperties anonymousSessionProperties,
            final Clock clock) {
        this.sessionActivityFlusher = sessionActivityFlusher;
        this.flushInterval = anonymousSessionProperties.activityFlushInterval();
        this.clock = clock;
        final int maxSize = anonymousSessionProperties.activityCacheMaxSize();
        // access-order LinkedHashMap + removeEldestEntry — JDK 만으로 LRU 상한 보장.
        // synchronizedMap 으로 감싸 모든 access 가 같은 monitor 에서 직렬화 (compute-like atomicity).
        // initialCapacity 는 maxSize 의 1.33 배 — 0.75 load factor 에서 resize 회피.
        this.lastFlushedAt = Collections.synchronizedMap(
                new LinkedHashMap<>(Math.max(16, (int) (maxSize / 0.75f) + 1), 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(final Map.Entry<String, Instant> eldest) {
                        return size() > maxSize;
                    }
                });
    }

    /**
     * sessionId 의 활동을 캐시에 마킹한다. 캐시 hit ({@code now - lastFlushedAt < flushInterval})
     * 이면 no-op. miss 면 {@link SessionActivityFlusher#flushOne(String)} 호출 + 캐시 갱신.
     *
     * <p>synchronized block 안에서 hit 판정/flush/put 까지 atomic 처리 — 동시 markActive 가 같은
     * sessionId 에 들어와도 한 번만 flush.
     */
    public void markActive(final String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        final Instant now = Instant.now(clock);

        synchronized (lastFlushedAt) {
            final Instant previous = lastFlushedAt.get(sessionId);
            if (previous != null && Duration.between(previous, now).compareTo(flushInterval) < 0) {
                // 캐시 hit — flush skip. get() 호출 자체가 access-order 갱신 (LRU 유지).
                return;
            }
            try {
                sessionActivityFlusher.flushOne(sessionId);
            } catch (final RuntimeException e) {
                // flush 실패 시 캐시에 마킹하지 않아 다음 호출이 재시도.
                // 04-security-policy.md: e.getMessage() 는 JpaSystemException 등이 SQL 본문
                // (sessionId 포함) 을 노출할 수 있어 exception 타입명만 로깅 (PII leak 방어).
                log.warn("session-activity flush 실패 reason={}", e.getClass().getSimpleName());
                return;
            }
            lastFlushedAt.put(sessionId, now);
        }
    }

    /**
     * sessionId 를 캐시에서 즉시 제거한다 — revoke (rotation / TTL batch) 후 호출.
     *
     * <p>spec §5-2 메모리 누수 방어 (PR #937 follow-up). revoke 된 sessionId 가 캐시에 남아도
     * SessionAuthGuard 가 DB 조회로 401 차단하므로 보안 문제는 없지만, 누적 시 OOM 위험을
     * 정상 라이프사이클 종료 시점에 직접 해소한다.
     *
     * <p>idempotent — 없는 sessionId 도 no-op. null/blank 도 no-op (호출자 방어 부담 최소화).
     */
    public void evict(final String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        lastFlushedAt.remove(sessionId);
    }

    /**
     * 테스트 용 — 캐시 비우기. 통합 테스트 간 격리용.
     *
     * <p>production code 에서 호출 금지. {@link #evict(String)} 가 정상 라이프사이클 진입점.
     */
    public void clearCache() {
        lastFlushedAt.clear();
    }

    /** 테스트 용 — 캐시 크기 확인 (LRU 상한 가드/메모리 누수 회귀 검증). */
    public int cacheSize() {
        return lastFlushedAt.size();
    }
}
