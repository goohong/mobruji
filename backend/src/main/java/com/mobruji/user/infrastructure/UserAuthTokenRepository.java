package com.mobruji.user.infrastructure;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mobruji.user.domain.UserAuthToken;

/**
 * {@link UserAuthToken} JPA repository.
 *
 * <p>spec: 인증·음역대 프로필 영속 (#1491). 조회 키는 원문 토큰의 SHA-256 hex (token_hash).
 */
public interface UserAuthTokenRepository extends JpaRepository<UserAuthToken, String> {

    Optional<UserAuthToken> findByTokenHash(String tokenHash);
}
