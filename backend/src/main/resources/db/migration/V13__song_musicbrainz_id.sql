-- V13__song_musicbrainz_id.sql — Song에 MusicBrainz Recording UUID(mb_id) 추가
--
-- spec: docs/features/musicbrainz-integration.md §5-5 — `mbId` 컬럼 promote.
--       closes #267 (mb_id 컬럼), 동반 #268 (MusicBrainzClient + backfill job).
-- 보호 영역 (CLAUDE.md §4) — `**/db/migration/**` 정보성 분류.
--
-- 의도:
--   - `mb_id`: MusicBrainz Recording UUID (36자). 매칭 전/실패 시 null.
--             글로벌 유일 식별자이므로 UNIQUE 제약 — 같은 mbid 가 두 Song 에 매칭되는 사고 방지.
--             NOTE: MySQL 8.4 / H2 MODE=MySQL 의 UNIQUE INDEX 는 NULL 을 여러 번 허용한다
--             (NULL != NULL). 미매칭 곡이 여러 개여도 충돌 없음.
--   - `isrc` 컬럼은 V3 (V3__song_isrc_confidence.sql) 에서 이미 추가됨 — 재추가 금지 (spec §5-5).
--
-- 추천 알고리즘 입력에 영향 없음 — 음역대/key/tempo 미설정이라 결정성 회귀 없다.
--
-- 방언: MySQL 8.4 (운영). 테스트는 H2 MODE=MySQL.

ALTER TABLE song ADD COLUMN mb_id VARCHAR(36) NULL;

CREATE UNIQUE INDEX uk_song_mb_id ON song (mb_id);
