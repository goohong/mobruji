-- V12__session_feedback.sql — recommendation BC (스와이프 세션 반응)
--
-- spec: docs/features/recommendation-feedback-loop.md §5-1 / §5-5
-- 보호 영역 (CLAUDE.md §4) — 마이그레이션. rev 사이클 추가 신중도(결정성/환경별 회귀).
--
-- 참조 엔티티: com.mobruji.recommendation.domain.SessionFeedback → session_feedback
--
-- feedback BC 의 like_feedback/bookmark_feedback(toggle, 추천 비영향)과 달리,
-- session_feedback 은 recommendation context 가 소유하며 next 추천 결합 신호로 환류된다.
-- 같은 (session_id, song_id) 재스와이프 시 reaction/created_at 을 덮어쓴다(upsert, application 레벨).
--
-- Song aggregate 참조는 ID-only (ADR-0005 §A-7). FK 제약은 두지 않는다 (application 레벨 join).
-- ADR-0013 cascade-delete 대상 (sessionId revoke 시 함께 삭제).
--
-- 방언: MySQL 8.4 (운영). 테스트는 H2 MODE=MySQL.

CREATE TABLE session_feedback (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    session_id VARCHAR(64) NOT NULL,
    song_id    BIGINT      NOT NULL,
    reaction   VARCHAR(8)  NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_session_feedback_session_song UNIQUE (session_id, song_id)
) ENGINE=InnoDB;

CREATE INDEX ix_session_feedback_session_created
    ON session_feedback (session_id, created_at);
