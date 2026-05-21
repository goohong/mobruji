-- V1__init.sql — Flyway baseline (ADR 0009)
--
-- 본 파일은 develop 시점 모든 엔티티 schema의 단일 진실원이다.
-- 이후 schema 변경은 V2__*, V3__* 누적 마이그레이션으로 작성한다.
--
-- 참조 엔티티 (com.mobruji.*):
--   - song.domain.Song                            → song
--   - voice.domain.VoiceRange                     → voice_range
--   - recommendation.domain.RecommendationRequestEntity
--                                                 → recommendation_request
--                                                 → recommendation_request_exclude_song (ElementCollection)
--   - recommendation.domain.Recommendation        → recommendation
--
-- 방언: MySQL 8.4 (운영). 테스트는 H2 MODE=MySQL.
-- ddl-auto=validate와 1:1 매칭되어야 한다 (컬럼명/타입/nullable/길이/인덱스).

-- ========================================================================
-- song
-- ========================================================================
CREATE TABLE song (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    title           VARCHAR(200) NOT NULL,
    artist          VARCHAR(200) NOT NULL,
    release_year    INTEGER          NULL,
    key_original    VARCHAR(24)  NOT NULL,
    bpm             INTEGER          NULL,
    mood            VARCHAR(16)      NULL,
    language        VARCHAR(32)      NULL,
    genre           VARCHAR(32)      NULL,
    tj_number       VARCHAR(16)      NULL,
    ky_number       VARCHAR(16)      NULL,
    metadata_source VARCHAR(24)  NOT NULL,
    low_midi        INTEGER          NULL,
    high_midi       INTEGER          NULL,
    difficulty      VARCHAR(16)      NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

CREATE INDEX ix_song_title  ON song (title);
CREATE INDEX ix_song_artist ON song (artist);

-- ========================================================================
-- voice_range
-- ========================================================================
CREATE TABLE voice_range (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    session_id        VARCHAR(64) NOT NULL,
    lowest_note_midi  INTEGER     NOT NULL,
    highest_note_midi INTEGER     NOT NULL,
    source_method     VARCHAR(32) NOT NULL,
    created_at        DATETIME(6) NOT NULL,
    updated_at        DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_voice_range_session_id UNIQUE (session_id)
) ENGINE=InnoDB;

-- ========================================================================
-- recommendation_request
-- ========================================================================
CREATE TABLE recommendation_request (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    session_id        VARCHAR(64) NOT NULL,
    voice_range_low   INTEGER     NOT NULL,
    voice_range_high  INTEGER     NOT NULL,
    mood              VARCHAR(16)     NULL,
    created_at        DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

-- ElementCollection 조인 테이블 (excludeSongIds).
-- FK 제약은 두지 않는다 (다른 도메인 join들과 일관 — application 레벨 join).
CREATE TABLE recommendation_request_exclude_song (
    recommendation_request_id BIGINT NOT NULL,
    song_id                   BIGINT NOT NULL
) ENGINE=InnoDB;

CREATE INDEX ix_rec_req_exclude_song_request
    ON recommendation_request_exclude_song (recommendation_request_id);

-- ========================================================================
-- recommendation
-- ========================================================================
CREATE TABLE recommendation (
    id                        BIGINT       NOT NULL AUTO_INCREMENT,
    recommendation_request_id BIGINT       NOT NULL,
    song_id                   BIGINT       NOT NULL,
    score                     DOUBLE       NOT NULL,
    match_reason              VARCHAR(200) NOT NULL,
    rank_position             INTEGER      NOT NULL,
    created_at                DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

CREATE INDEX ix_recommendation_request_rank
    ON recommendation (recommendation_request_id, rank_position);
