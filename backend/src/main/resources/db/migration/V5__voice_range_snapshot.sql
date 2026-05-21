-- V5__voice_range_snapshot.sql — voice_range_snapshot 신설 (insert-only 시계열)
--
-- spec: docs/features/voice-range-progress.md §5-1, §5-5 (closes #230, spec PR A).
-- 보호 영역 (CLAUDE.md §4) — PR 에 `needs-human-review` 라벨 부여.
--
-- 의도:
--   - voice_range 는 "현재 값"을, voice_range_snapshot 은 "변경 이력"을 담당하는 CQRS-라이트 분리.
--   - voice_range upsert 와 동일 트랜잭션에서 1행 insert (덮어쓰기 금지).
--   - 결정성 영향 없음 — 추천 입력에 사용되지 않는다 (read-side 시계열 전용).
--
-- 방언: MySQL 8.4 (운영). 테스트는 H2 MODE=MySQL.

CREATE TABLE voice_range_snapshot (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    session_id    VARCHAR(64) NOT NULL,
    low_midi      INTEGER     NOT NULL,
    high_midi     INTEGER     NOT NULL,
    source_method VARCHAR(32) NOT NULL,
    measured_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

CREATE INDEX ix_voice_range_snapshot_session_measured
    ON voice_range_snapshot (session_id, measured_at);
