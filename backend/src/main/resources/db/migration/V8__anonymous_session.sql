-- V8__anonymous_session.sql — 익명 sessionId 라이프사이클 엔티티 (ADR-0013, #887).
--
-- spec: docs/features/anonymous-session-lifecycle.md §5-1, §5-7 (PR 2).
-- ADR: docs/decisions/0013-sessionid-ttl-rotation.md §D-1 (TTL 180일 inactive sliding).
--
-- 보호 영역 (CLAUDE.md §4) — 본 PR 은 `needs-human-review` 라벨 부여.
--
-- 의도:
--   - 익명 sessionId 의 라이프사이클(생성·활동·revoke) 을 영속할 단일 진실 테이블.
--   - revoked_at = NULL 행이 활성 세션. revoked_at != NULL 은 TTL/회전/머지로 폐기됨.
--   - 다른 도메인 (voice_range, like_feedback 등) 의 session_id 컬럼은 FK 없이 의미상 참조
--     (soft reference) — cascade-delete 는 application 트랜잭션이 수행 (spec §5-1 단서).
--   - last_seen_at INDEX 는 TTL 만료 batch 의 selective query 용 (spec §5-3).
--   - revoked_at INDEX 는 활성/폐기 분리 조회용 (관측/디버깅).
--
-- backfill (기존 sessionId 들의 행 일괄 생성, spec §5-7 V<next+1>) 은 별 PR 로 분리 권장.
-- 본 PR 은 신규 테이블만 — 운영 락 부담 최소화.
--
-- 방언: MySQL 8.4 (운영). 테스트는 H2 MODE=MySQL.

CREATE TABLE anonymous_session (
    session_id     VARCHAR(64) NOT NULL,
    first_seen_at  DATETIME(6) NOT NULL,
    last_seen_at   DATETIME(6) NOT NULL,
    revoked_at     DATETIME(6)     NULL,
    revoked_reason VARCHAR(32)     NULL,
    PRIMARY KEY (session_id)
) ENGINE=InnoDB;

CREATE INDEX ix_anonymous_session_last_seen_at
    ON anonymous_session (last_seen_at);

CREATE INDEX ix_anonymous_session_revoked_at
    ON anonymous_session (revoked_at);
