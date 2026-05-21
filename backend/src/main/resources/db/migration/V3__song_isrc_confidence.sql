-- V3__song_isrc_confidence.sql — Song에 ISRC + metadataConfidence 추가
--
-- spec: docs/features/song-metadata-source.md §5-1 — `isrc`/`metadataConfidence`
--       잠정 필드를 본진 컬럼으로 promote. closes #44 (ISRC), closes #203 (confidence).
-- 보호 영역 (CLAUDE.md §4) — PR에 `needs-human-review` 라벨 부여.
--
-- 의도:
--   - `isrc`: International Standard Recording Code (12자, ISO 3901). 외부 분석 시 채움.
--             수기 시드는 null. 글로벌 유일 식별자이므로 UNIQUE 제약.
--             NOTE: MySQL 8.4 의 UNIQUE INDEX 는 NULL 을 여러 번 허용한다
--             (NULL != NULL). PostgreSQL 의 partial index 가 아닌 일반 UNIQUE 로 충분.
--   - `metadata_confidence`: 0.0 ~ 1.0. 기본 1.0 = MANUAL_SEED 수기 입력 신뢰도.
--                             AUDIO_ANALYSIS backfill 시 result.confidence 가 저장된다.
--
-- 추천 알고리즘 입력에 영향 없음 — 결정성 회귀 없다.
--
-- 방언: MySQL 8.4 (운영). 테스트는 H2 MODE=MySQL.

ALTER TABLE song ADD COLUMN isrc VARCHAR(12) NULL;
ALTER TABLE song ADD COLUMN metadata_confidence DOUBLE NOT NULL DEFAULT 1.0;

CREATE UNIQUE INDEX uk_song_isrc ON song (isrc);
