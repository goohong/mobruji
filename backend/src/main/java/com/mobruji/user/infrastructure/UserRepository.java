package com.mobruji.user.infrastructure;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mobruji.user.domain.User;

/**
 * {@link User} JPA repository.
 *
 * <p>spec: 인증·음역대 프로필 영속 (#1491).
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /** 소문자 정규화된 이메일로 LOCAL 계정 조회. 회원가입 중복 검사 + 로그인 식별. */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
