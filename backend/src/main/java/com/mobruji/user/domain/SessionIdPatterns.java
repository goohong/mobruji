package com.mobruji.user.domain;

/**
 * sessionId 형식 검증용 공용 정규식 상수.
 *
 * <p>spec: {@code docs/features/anonymous-session-lifecycle.md} §5-4, ADR-0011 — sessionId 는 client 가
 * 발급한 UUIDv4. 본 상수는 {@code @Pattern(regexp = SessionIdPatterns.UUID_V4)} 형태로 DTO 검증에
 * 재사용되어 SessionRotateRequest 와 다른 *Request DTO 간 형식 일관성을 유지한다 (#948).
 *
 * <p>regex 자체는 소문자 hex 8-4-4-4-12 패턴이며 UUIDv4 의 version/variant nibble 까지는 검사하지 않는다.
 * 이는 client (web/mobile) 가 표준 UUIDv4 generator 로 생성한 값이라는 전제 하의 가벼운 형식 가드 —
 * 의도적으로 strict 하지 않게 하여 generator 호환성을 깨지 않는다.
 */
public final class SessionIdPatterns {

    /** UUIDv4 형식 (소문자 hex 8-4-4-4-12). */
    public static final String UUID_V4 = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    /** {@code @Pattern.message} 공통 문구 — 검증 실패 응답에서 client 가 일관되게 인지. */
    public static final String UUID_V4_MESSAGE = "UUIDv4 format required";

    private SessionIdPatterns() {
        // 상수 유틸 — 인스턴스화 금지
    }
}
