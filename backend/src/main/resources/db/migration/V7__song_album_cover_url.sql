-- V7__song_album_cover_url.sql — Song에 albumCoverUrl 컬럼 추가 (이슈 #322, PR A).
--
-- spec: 이슈 #322 — 곡 카드 앨범 커버 이미지. iTunes Search API 를 1차 backfill 출처로 사용하고
-- 후속 사이클에서 MusicBrainz Cover Art / Spotify 로 우선순위 통합한다.
-- 보호 영역 (CLAUDE.md §4) — PR 에 `needs-human-review` 라벨 부여.
--
-- 의도:
--   - `album_cover_url`: 곡 카드에 표시할 앨범 커버 이미지 URL.
--     nullable — 매칭 실패한 곡은 null 로 두고 UI 에서 placeholder 로 처리한다.
--     인덱스 불필요 — 조회 키가 아니라 단순 표시 필드.
--   - VARCHAR(512): iTunes Search API 의 artworkUrl100 형식 길이 여유 확보
--     (`https://is1-ssl.mzstatic.com/image/thumb/.../600x600bb.jpg` 약 200자).
--
-- 추천 알고리즘 입력에 영향 없음 — 결정성 회귀 없음 (UX 표시 전용).
--
-- 방언: MySQL 8.4 (운영). 테스트는 H2 MODE=MySQL.

ALTER TABLE song ADD COLUMN album_cover_url VARCHAR(512) NULL;
