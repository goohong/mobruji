package com.mobruji.user.infrastructure;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mobruji.user.domain.AnonymousSession;

/**
 * {@link AnonymousSession} JPA repository.
 *
 * <p>spec: {@code docs/features/anonymous-session-lifecycle.md} §5-1, §5-3.
 */
public interface AnonymousSessionRepository extends JpaRepository<AnonymousSession, String> {

    /**
     * TTL 만료 batch 대상 sessionId 조회.
     *
     * <p>spec §5-3: {@code lastSeenAt + 180일 < now()} AND {@code revokedAt IS NULL} 인 sessionId 를
     * 최대 {@code limit} 건까지 반환. cutoff 는 호출자(스케줄러)가 계산해서 전달한다.
     *
     * <p>반환은 sessionId 만 (전체 엔티티가 아닌) — batch loop 가 sessionId 별 독립 트랜잭션으로
     * cascade-delete 를 수행하므로, 첫 조회는 식별자만 받아 락 범위를 최소화한다.
     */
    @Query("""
            SELECT a.sessionId
            FROM AnonymousSession a
            WHERE a.lastSeenAt < :cutoff
              AND a.revokedAt IS NULL
            ORDER BY a.lastSeenAt ASC
            """)
    List<String> findInactiveSessionIds(@Param("cutoff") LocalDateTime cutoff, Limit limit);
}
