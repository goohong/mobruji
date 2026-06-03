package com.mobruji.user.domain;

import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 로그인 세션을 표현하는 불투명(opaque) 인증 토큰.
 *
 * <p>spec: 인증·음역대 프로필 영속 (#1491). 원문 토큰은 발급 응답에서 1회만 클라이언트에 노출되고,
 * 서버는 SHA-256 해시({@code tokenHash})만 영속한다 — DB 유출 시에도 원문 토큰을 복원/재사용할 수
 * 없다 ({@code 04-security-policy.md} 시크릿 at-rest 보호).
 *
 * <ul>
 * <li>{@code tokenHash} 는 원문 토큰의 SHA-256 hex(64자) — PK.</li>
 * <li>{@code expiresAt} 경과 또는 {@code revokedAt != null} 이면 비활성. 검증은
 * {@link #isActiveAt(LocalDateTime)} 가 단일 판단.</li>
 * </ul>
 */
@Getter
@Entity
@Table(
        name = "user_auth_token", indexes = {
                @Index(name = "ix_user_auth_token_user_id", columnList = "user_id"),
                @Index(name = "ix_user_auth_token_expires_at", columnList = "expires_at")
        })
@AllArgsConstructor(access = AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAuthToken {

    /** SHA-256 hex 표현 길이. */
    public static final int TOKEN_HASH_LENGTH = 64;

    @Id
    @Column(name = "token_hash", nullable = false, length = TOKEN_HASH_LENGTH)
    private String tokenHash;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    /**
     * 신규 토큰을 발급한다.
     *
     * @param tokenHash 원문 토큰의 SHA-256 hex. null/blank 금지.
     * @param userId 토큰 소유 사용자 id. null 금지.
     * @param expiresAt 만료 시각. {@code now} 이후여야 한다.
     */
    public static UserAuthToken issue(
            final String tokenHash,
            final Long userId,
            final LocalDateTime expiresAt) {
        Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (tokenHash.isBlank()) {
            throw new IllegalArgumentException("tokenHash must not be blank");
        }
        final LocalDateTime now = LocalDateTime.now();
        if (!expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("expiresAt must be after now");
        }
        return new UserAuthToken(tokenHash, userId, now, expiresAt, null);
    }

    /** {@code at} 시점에 활성(미만료 + 미폐기)인지. */
    public boolean isActiveAt(final LocalDateTime at) {
        Objects.requireNonNull(at, "at must not be null");
        return this.revokedAt == null && this.expiresAt.isAfter(at);
    }

    public void revoke() {
        if (this.revokedAt == null) {
            this.revokedAt = LocalDateTime.now();
        }
    }
}
