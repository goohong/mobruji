-- V11__user_account.sql — 영속 사용자 계정 + 음역대 프로필 + 인증 토큰 (#1491).
--
-- 의도:
--   - 지금까지 회원 개념 없이 익명 sessionId(V8 anonymous_session)만 존재했다. 음역대는 요청마다
--     전달되어 재방문 시 매번 재입력이 필요했다. 영속 계정으로 음역대/성별 프로필을 한 번 저장하면
--     재방문 시 재입력이 불필요해진다 (페르소나 P-A 직격).
--   - app_user: 이메일 회원가입(LOCAL) 1차 지원. auth_provider/provider_user_id 컬럼으로 소셜
--     (KAKAO/GOOGLE) 확장에 대비한 스키마만 미리 둔다 — 소셜 OAuth 교환 흐름 구현은 후속 PR.
--   - 비밀번호는 평문 저장 금지. application 의 PasswordHasher 가 PBKDF2(salt+iteration) 인코딩
--     문자열을 password_hash 에 저장한다. 소셜 전용 계정은 password_hash = NULL.
--   - user_auth_token: opaque 토큰의 SHA-256 해시만 저장(token_hash) — DB 유출 시에도 원문 토큰
--     재사용 불가. 만료(expires_at)/폐기(revoked_at) 로 세션 수명 관리.
--
-- 테이블명 `app_user` — `user` 는 MySQL 예약어라 백틱 없이 쓰기 위해 prefix.
--
-- 방언: MySQL 8.4 (운영). 테스트는 H2 MODE=MySQL.

CREATE TABLE app_user (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    email                VARCHAR(254)     NULL,
    password_hash        VARCHAR(255)     NULL,
    auth_provider        VARCHAR(16)  NOT NULL,
    provider_user_id     VARCHAR(128)     NULL,
    gender               VARCHAR(16)      NULL,
    vocal_range_low_midi INT              NULL,
    vocal_range_high_midi INT             NULL,
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

-- 이메일은 LOCAL 계정의 로그인 식별자 — 소문자 정규화 후 유일.
CREATE UNIQUE INDEX uk_app_user_email
    ON app_user (email);

-- 소셜 계정은 (provider, provider_user_id) 조합으로 유일. LOCAL 계정은 둘 다 의미 없음(provider=LOCAL).
CREATE UNIQUE INDEX uk_app_user_provider_identity
    ON app_user (auth_provider, provider_user_id);

CREATE TABLE user_auth_token (
    token_hash VARCHAR(64) NOT NULL,
    user_id    BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6)     NULL,
    PRIMARY KEY (token_hash)
) ENGINE=InnoDB;

CREATE INDEX ix_user_auth_token_user_id
    ON user_auth_token (user_id);

CREATE INDEX ix_user_auth_token_expires_at
    ON user_auth_token (expires_at);
