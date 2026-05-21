-- V2__likes_bookmarks.sql — feedback BC (Like/Bookmark)
--
-- spec: docs/features/recommendation-history-and-feedback.md §5-5
-- 보호 영역 (CLAUDE.md §4) — PR에 `needs-human-review` 라벨 부여.
--
-- 참조 엔티티 (com.mobruji.feedback.domain.*):
--   - Like      → like_feedback
--   - Bookmark  → bookmark_feedback
--
-- Song aggregate 참조는 ID-only (ADR 0005 §A-7). FK 제약은 두지 않는다
-- (V1 recommendation 테이블들과 일관 — application 레벨 join).
--
-- 방언: MySQL 8.4 (운영). 테스트는 H2 MODE=MySQL.

-- ========================================================================
-- like_feedback
--   - `like`는 MySQL 예약어이므로 테이블명에 `_feedback` suffix를 둔다.
-- ========================================================================
CREATE TABLE like_feedback (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    session_id VARCHAR(64) NOT NULL,
    song_id    BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_like_feedback_session_song UNIQUE (session_id, song_id)
) ENGINE=InnoDB;

CREATE INDEX ix_like_feedback_session_created
    ON like_feedback (session_id, created_at);

-- ========================================================================
-- bookmark_feedback
-- ========================================================================
CREATE TABLE bookmark_feedback (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    session_id VARCHAR(64) NOT NULL,
    song_id    BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_bookmark_feedback_session_song UNIQUE (session_id, song_id)
) ENGINE=InnoDB;

CREATE INDEX ix_bookmark_feedback_session_created
    ON bookmark_feedback (session_id, created_at);
