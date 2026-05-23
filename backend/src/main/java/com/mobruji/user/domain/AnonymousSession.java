package com.mobruji.user.domain;

import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 익명 sessionId 의 라이프사이클(생성·활동·만료/회전/머지)을 영속하는 엔티티.
 *
 * <p>spec: {@code docs/features/anonymous-session-lifecycle.md} §5-1, ADR-0013.
 *
 * <ul>
 * <li>{@code sessionId} 는 client 가 발급한 UUIDv4 (ADR-0011) — 본 엔티티의 외부 식별자이자 PK.</li>
 * <li>{@code firstSeenAt} 은 최초 진입 시각, {@code lastSeenAt} 은 sliding TTL 평가 기준점.</li>
 * <li>{@code revokedAt} != null 인 행은 만료/회전/머지로 폐기됨. revoked sessionId 재사용은
 * {@code SessionAuthGuard} 가 401 로 차단 (본 PR 범위 외, 후속 PR 3).</li>
 * <li>cascade-delete 대상 데이터 테이블(voice_range, voice_range_snapshot, like, bookmark,
 * recommendation 등)은 FK 없이 의미상 참조 — soft reference. cascade 는 application 트랜잭션이
 * 수행 (spec §5-1).</li>
 * </ul>
 *
 * <p>본 PR 범위는 엔티티/마이그레이션/회전 endpoint/TTL 만료 batch 만 포함. SessionAuthGuard 의
 * 만료 게이트 확장(spec §5-2)과 in-memory {@code SessionActivityTracker}(§5-2 의 5분 캐시)는
 * 후속 PR 3 에서 도입.
 */
@Getter
@Entity
@Table(
        name = "anonymous_session", indexes = {
                @Index(name = "ix_anonymous_session_last_seen_at", columnList = "last_seen_at"),
                @Index(name = "ix_anonymous_session_revoked_at", columnList = "revoked_at")
        })
@AllArgsConstructor(access = AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnonymousSession {

    /** sessionId 컬럼 최대 길이 — ADR-0011 UUIDv4(36자) + 호환 여유. */
    public static final int SESSION_ID_MAX_LENGTH = 64;

    @Id
    @Column(name = "session_id", nullable = false, length = SESSION_ID_MAX_LENGTH)
    private String sessionId;

    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoked_reason", length = 32)
    private RevokedReason revokedReason;

    /**
     * 신규 sessionId 등록 (bootstrap 또는 회전 endpoint 의 newSessionId 발급 시점).
     *
     * @param sessionId client 가 발급한 UUIDv4 (ADR-0011). null/blank 금지.
     * @return revokedAt = null, firstSeenAt = lastSeenAt = now() 인 새 엔티티
     */
    public static AnonymousSession create(final String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (sessionId.length() > SESSION_ID_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "sessionId length exceeds " + SESSION_ID_MAX_LENGTH + ": " + sessionId.length());
        }
        final LocalDateTime now = LocalDateTime.now();
        return new AnonymousSession(sessionId, now, now, null, null);
    }

    /**
     * 활성 확인 후 {@code lastSeenAt} 을 갱신한다 (sliding TTL window).
     *
     * <p>spec §5-2: 매 요청마다 호출 시 DB write 부담이 크므로 호출자(서비스 또는 후속 PR 3 의
     * {@code SessionActivityTracker})가 5분 캐시 윈도우로 묶어서 호출하는 것을 권장. 본 메서드
     * 자체는 호출 시점에 즉시 갱신한다.
     */
    public void touch() {
        if (this.revokedAt != null) {
            throw new IllegalStateException("cannot touch revoked session");
        }
        this.lastSeenAt = LocalDateTime.now();
    }

    /**
     * sessionId 를 revoke 처리한다 (TTL 만료 / 사용자 회전 / 계정 머지).
     *
     * <p>이미 revoked 상태인 경우 idempotent — 기존 revokedAt/Reason 을 보존한다 (중복 batch
     * 재실행 안전).
     */
    public void revoke(final RevokedReason revokedReason) {
        Objects.requireNonNull(revokedReason, "revokedReason must not be null");
        if (this.revokedAt != null) {
            return;
        }
        this.revokedAt = LocalDateTime.now();
        this.revokedReason = revokedReason;
    }

    public boolean isRevoked() {
        return this.revokedAt != null;
    }
}
