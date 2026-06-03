package com.mobruji.user.application;

import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

/**
 * sessionId 단위 cascade-delete 트랜잭션.
 *
 * <p>spec: {@code docs/features/anonymous-session-lifecycle.md} §5-3, ADR-0013 §D-3
 * (default cascade-delete).
 *
 * <p>대상 테이블 (FK 없이 sessionId 컬럼만 갖는 soft reference):
 *
 * <ul>
 * <li>{@code voice_range_snapshot}</li>
 * <li>{@code voice_range}</li>
 * <li>{@code like_feedback}</li>
 * <li>{@code bookmark_feedback}</li>
 * <li>{@code session_feedback}</li>
 * <li>{@code recommendation_request_exclude_song} (FK to recommendation_request)</li>
 * <li>{@code recommendation} (FK to recommendation_request)</li>
 * <li>{@code recommendation_request}</li>
 * </ul>
 *
 * <p>recommendation_request_exclude_song / recommendation 은 recommendation_request 에 의존하므로
 * 부모를 마지막에 삭제 (자식 → 부모 순). FK 가 실제로 없어 임의 순서로도 동작하지만 의미적 순서
 * 유지.
 *
 * <p>각 호출은 {@code @Transactional(REQUIRES_NEW)} — batch loop 안에서 sessionId 단위 독립
 * 트랜잭션 보장 (spec §3 비기능 "트랜잭션 안전성", 락 범위/롤백 부담 분산).
 *
 * <p>JdbcTemplate 사용 이유: JPA 의 cascade 가 FK 미보유 테이블에서는 무력하고, 한번에 다중
 * 테이블에 대한 native DELETE 가 가장 명확. 보호 영역 확장(application.yml) 없음.
 *
 * <p><b>호출자 권한 검증 책임.</b> 본 클래스는 sessionId 만 받아 cascade DELETE 를 수행하며 권한
 * 검증을 안 한다. 호출자가 사전에 {@link com.mobruji.user.application.SessionAuthGuard} / cron schedule
 * 등으로 권한을 확인해야 한다. 신규 호출자 추가 시 권한 검증 누락 위험 review 필수.
 */
@Slf4j
@Component
public class SessionDataCascadeDeleter {

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public SessionDataCascadeDeleter(final JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 한 sessionId 의 모든 데이터 cascade-delete + 삭제된 총 row 수 반환.
     *
     * <p>각 테이블의 DELETE 결과 row 수 합. 호출자는 본 합계를 Discord 알림/메트릭에 활용 가능.
     *
     * @param sessionId revoke 처리할 sessionId. null/blank 금지.
     * @return 모든 대상 테이블에서 삭제된 row 수의 합 ({@code anonymous_session} 자체 행은 미포함 —
     *         호출자가 별도로 {@code revoke()} 후 save 한다)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long cascadeDelete(final String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }

        long deleted = 0L;

        // 1) recommendation_request 자식 (FK to recommendation_request_id) — 부모 먼저 식별 후 자식 제거
        final List<Long> requestIds = jdbcTemplate.queryForList(
                "SELECT id FROM recommendation_request WHERE session_id = ?",
                Long.class, sessionId);
        if (!requestIds.isEmpty()) {
            final String inClause = String.join(",", requestIds.stream().map(id -> "?").toList());
            final Object[] requestIdArgs = requestIds.toArray();

            deleted += jdbcTemplate.update(
                    "DELETE FROM recommendation_request_exclude_song WHERE recommendation_request_id IN (" + inClause
                            + ")",
                    requestIdArgs);
            deleted += jdbcTemplate.update(
                    "DELETE FROM recommendation WHERE recommendation_request_id IN (" + inClause + ")",
                    requestIdArgs);
        }

        // 2) session_id 컬럼을 직접 가진 테이블
        deleted += jdbcTemplate.update("DELETE FROM recommendation_request WHERE session_id = ?", sessionId);
        deleted += jdbcTemplate.update("DELETE FROM voice_range_snapshot WHERE session_id = ?", sessionId);
        deleted += jdbcTemplate.update("DELETE FROM voice_range WHERE session_id = ?", sessionId);
        deleted += jdbcTemplate.update("DELETE FROM like_feedback WHERE session_id = ?", sessionId);
        deleted += jdbcTemplate.update("DELETE FROM bookmark_feedback WHERE session_id = ?", sessionId);
        deleted += jdbcTemplate.update("DELETE FROM session_feedback WHERE session_id = ?", sessionId);

        // sessionId 원문은 로그 금지 (ADR-0011 §Decision). 행 수만 노출.
        log.info("anonymous-session cascade-delete: deleted_rows={}", deleted);
        return deleted;
    }
}
