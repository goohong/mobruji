package com.mobruji.user.application;

import java.util.Locale;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.mobruji.user.domain.EmailAlreadyRegisteredException;
import com.mobruji.user.domain.InvalidCredentialsException;
import com.mobruji.user.domain.User;
import com.mobruji.user.infrastructure.UserRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 사용자 계정 라이프사이클 — 이메일 회원가입/로그인 + 음역대/성별 프로필 조회·갱신 + Bearer 토큰 인증.
 *
 * <p>spec: 인증·음역대 프로필 영속 (#1491). 비밀번호 해시는 {@link PasswordHasher}, 토큰 발급/검증은
 * {@link UserAuthTokenService} 에 위임한다.
 */
@Slf4j
@Service
public class UserAccountService {

    private static final String BEARER_PREFIX = "Bearer ";

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final UserAuthTokenService userAuthTokenService;

    public UserAccountService(
            final UserRepository userRepository,
            final PasswordHasher passwordHasher,
            final UserAuthTokenService userAuthTokenService) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.userAuthTokenService = userAuthTokenService;
    }

    /**
     * 이메일 회원가입. 이메일은 소문자 정규화 후 유일성 검사. 중복이면
     * {@link EmailAlreadyRegisteredException}(409).
     */
    @Transactional
    public AuthResult signup(final SignupCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        final String email = normalizeEmail(command.email());
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException();
        }
        final User user = userRepository.save(User.createLocal(
                email,
                passwordHasher.hash(command.rawPassword()),
                command.gender(),
                command.vocalRangeLowMidi(),
                command.vocalRangeHighMidi()));
        log.info("user signed up — id={}", user.getId());
        return new AuthResult(user, userAuthTokenService.issue(user.getId()));
    }

    /**
     * 이메일 로그인. 이메일 미존재와 비밀번호 불일치를 동일 {@link InvalidCredentialsException}(401)
     * 로 통일 (계정 열거 방어).
     */
    @Transactional
    public AuthResult login(final LoginCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        final String email = normalizeEmail(command.email());
        final User user = userRepository.findByEmail(email)
                .orElseThrow(InvalidCredentialsException::new);
        if (user.getPasswordHash() == null
                || !passwordHasher.matches(command.rawPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        return new AuthResult(user, userAuthTokenService.issue(user.getId()));
    }

    @Transactional(readOnly = true)
    public User getProfile(final Long userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
    }

    @Transactional
    public User updateProfile(final Long userId, final UpdateProfileCommand command) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        final User user = getProfile(userId);
        user.updateProfile(command.gender(), command.vocalRangeLowMidi(), command.vocalRangeHighMidi());
        return user;
    }

    /**
     * {@code Authorization: Bearer <token>} 헤더를 검증하고 인증된 userId 를 반환한다. 헤더 누락/형식
     * 오류/무효 토큰은 모두 401.
     */
    public Long authenticate(final String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing bearer token");
        }
        final String rawToken = authorizationHeader.substring(BEARER_PREFIX.length());
        return userAuthTokenService.authenticate(rawToken)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid or expired token"));
    }

    private static String normalizeEmail(final String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
