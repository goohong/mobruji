package com.mobruji.user.domain;

/**
 * {@link AnonymousSession} 의 revoke 사유 enum.
 *
 * <p>spec: {@code docs/features/anonymous-session-lifecycle.md} §5-1, ADR-0013 §D-5 의 라벨
 * 화이트리스트 ({@code reason=ttl|user_rotate|account_merge}) 단일 진실. Micrometer 카운터
 * {@code mobruji.session.expired{reason=...}} 라벨 값과 1:1 대응 (관측성-baseline §5-7 enum
 * 룰 준수).
 */
public enum RevokedReason {

    /** TTL 만료 batch (180일 inactive). spec §5-3. */
    TTL,
    /** 사용자 트리거 회전 ({@code POST /api/v1/sessions/rotate}). spec §5-4. */
    USER_ROTATE,
    /** v0.4 계정 머지 시 sessionId revoke. spec §5-5. 본 PR 범위 외. */
    ACCOUNT_MERGE;

    /**
     * Micrometer 라벨 표현 — enum name 의 lowercase ({@code TTL → "ttl"} 등).
     * observability-baseline.md §5-7 라벨 룰: 모두 lowercase + underscore.
     */
    public String toMetricLabel() {
        return this.name().toLowerCase(java.util.Locale.ROOT);
    }
}
