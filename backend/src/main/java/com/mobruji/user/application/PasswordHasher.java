package com.mobruji.user.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.Objects;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.springframework.stereotype.Component;

/**
 * 비밀번호 해시·검증. JDK 내장 PBKDF2WithHmacSHA256 만 사용해 외부 의존성/시크릿 없이 salt+iteration
 * 해시를 만든다.
 *
 * <p>spec: 인증·음역대 프로필 영속 (#1491). 평문 비밀번호는 어떤 경우에도 영속·로그하지 않는다
 * ({@code 04-security-policy.md}). 인코딩 포맷은 자기서술적이라 향후 파라미터(iteration) 상향 시에도
 * 기존 해시를 그대로 검증할 수 있다:
 *
 * <pre>pbkdf2-sha256${iterations}${base64Salt}${base64Hash}</pre>
 */
@Component
public class PasswordHasher {

    static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    static final String PREFIX = "pbkdf2-sha256";
    static final int ITERATIONS = 210_000;
    static final int SALT_BYTES = 16;
    static final int KEY_BITS = 256;

    private static final Base64.Encoder ENCODER = Base64.getEncoder();
    private static final Base64.Decoder DECODER = Base64.getDecoder();

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 평문 비밀번호를 인코딩된 해시 문자열로 변환한다. 호출마다 새 salt 를 생성하므로 같은 입력도 매번
     * 다른 결과가 나온다.
     */
    public String hash(final String rawPassword) {
        Objects.requireNonNull(rawPassword, "rawPassword must not be null");
        final byte[] salt = new byte[SALT_BYTES];
        secureRandom.nextBytes(salt);
        final byte[] derived = pbkdf2(rawPassword, salt, ITERATIONS);
        return PREFIX + "$" + ITERATIONS + "$" + ENCODER.encodeToString(salt) + "$" + ENCODER.encodeToString(derived);
    }

    /**
     * 평문 비밀번호가 인코딩된 해시와 일치하는지 상수시간 비교한다. 인코딩 포맷이 깨졌으면 false.
     */
    public boolean matches(final String rawPassword, final String encodedHash) {
        Objects.requireNonNull(rawPassword, "rawPassword must not be null");
        Objects.requireNonNull(encodedHash, "encodedHash must not be null");
        final String[] parts = encodedHash.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        final int iterations;
        final byte[] salt;
        final byte[] expected;
        try {
            iterations = Integer.parseInt(parts[1]);
            salt = DECODER.decode(parts[2]);
            expected = DECODER.decode(parts[3]);
        } catch (final IllegalArgumentException malformed) {
            return false;
        }
        final byte[] actual = pbkdf2(rawPassword, salt, iterations);
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] pbkdf2(final String rawPassword, final byte[] salt, final int iterations) {
        final PBEKeySpec spec = new PBEKeySpec(rawPassword.toCharArray(), salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (final NoSuchAlgorithmException | InvalidKeySpecException cryptoFailure) {
            throw new IllegalStateException("PBKDF2 hashing failed", cryptoFailure);
        } finally {
            spec.clearPassword();
        }
    }
}
