-- 곡 검색 — 초성 검색(ChosungSearch) 파생 컬럼 + index (spec song-search-and-filter.md §5-5).
-- 파생값(한글 음절 → 초성열)은 SQL CASE 로 표현 불가 → 기존 row backfill 은 Java(ChosungBackfillRunner,
-- @Profile("!test"))가 부팅 시 멱등 수행. 신규 row 는 Song.create() 에서 동기 파생.
ALTER TABLE song ADD COLUMN title_chosung  VARCHAR(200) NULL;
ALTER TABLE song ADD COLUMN artist_chosung VARCHAR(200) NULL;

-- prefix LIKE ('ㅂㄹㄷ%') 가 B-tree index 를 타도록 — leading wildcard 없는 초성 검색 전제 (§5-7).
CREATE INDEX ix_song_title_chosung  ON song (title_chosung);
CREATE INDEX ix_song_artist_chosung ON song (artist_chosung);
