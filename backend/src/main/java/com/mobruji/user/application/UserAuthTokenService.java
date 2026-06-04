package com.mobruji.user.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mobruji.user.domain.UserAuthToken;
import com.mobruji.user.infrastructure.UserAuthTokenRepository;

/**
 * 불투명 인증 토큰의 발급·검증·폐기.
 *
 * <p>spec: 인증·음역대 프로필 영속 (#1491).
 *
 * <ul>
 * <li>발급: 256bit 난수를 base64url 로 인코딩해 원문 토큰을 만들고, SHA-256 해시만 영속한다. 원문은
 * 반환값으로 1회만 노출 — 서버는 원문을 보관하지 않는다.</li>
 * <li>검증: presented 원문을 동일 해시로 변환해 조회하고 {@link UserAuthToken#isActiveAt} 로 만료/폐기를
 * 판단한다.</li>
 * </ul>
 */
@Service
public class UserAuthTokenService {

    private static final int TOKEN_BYTES = 32;

    private final UserAuthTokenRepository userAuthTokenRepository;
    private final UserAuthProperties userAuthProperties;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Base64.Encoder urlEncoder = Base64.getUrlEncoder().withoutPadding();

    public UserAuthTokenService(
            final UserAuthTokenRepository userAuthTokenRepository,
            final UserAuthProperties userAuthProperties) {
        this.userAuthTokenRepository = userAuthTokenRepository;
        this.userAuthProperties = userAuthProperties;
    }

    /**
     * 새 토큰을 발급하고 원문 토큰을 반환한다. 원문은 호출자가 응답으로 전달한 뒤 폐기하며, 서버에는
     * 해시만 남는다.
     */
    @Transactional
    public IssuedToken issue(final Long userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        final byte[] raw = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(raw);
        final String rawToken = urlEncoder.encodeToString(raw);
        final LocalDateTime expiresAt = LocalDateTime.now().plus(userAuthProperties.tokenTtl());
        userAuthTokenRepository.save(UserAuthToken.issue(sha256Hex(rawToken), userId, expiresAt));
        return new IssuedToken(rawToken, expiresAt);
    }

    /**
     * presented 원문 토큰이 활성 토큰이면 소유 userId 를 반환한다. 미존재/만료/폐기면 empty.
     */
    @Transactional(readOnly = true)
    public Optional<Long> authenticate(final String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        return userAuthTokenRepository.findByTokenHash(sha256Hex(rawToken))
                .filter(token -> token.isActiveAt(LocalDateTime.now()))
                .map(UserAuthToken::getUserId);
    }

    static String sha256Hex(final String value) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(digest.length * 2);
            for (final byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (final NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    /** 발급 결과 — 원문 토큰(1회 노출) + 만료 시각. */
    public record IssuedToken(
            String rawToken,
            LocalDateTime expiresAt
    ) {
    }
}
