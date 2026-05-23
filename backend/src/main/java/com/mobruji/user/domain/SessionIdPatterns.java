package com.mobruji.user.domain;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * sessionId 형식 검증용 공용 정규식 상수 + 생성 헬퍼.
 *
 * <p>spec: {@code docs/features/anonymous-session-lifecycle.md} §5-4, ADR-0011 — sessionId 는 client 가
 * 발급한 UUIDv4. 본 상수는 {@code @Pattern(regexp = SessionIdPatterns.UUID_V4)} 형태로 DTO 검증에
 * 재사용되어 SessionRotateRequest 와 다른 *Request DTO 간 형식 일관성을 유지한다 (#948).
 *
 * <p>regex 자체는 소문자 hex 8-4-4-4-12 패턴이며 UUIDv4 의 version/variant nibble 까지는 검사하지 않는다.
 * 이는 client (web/mobile) 가 표준 UUIDv4 generator 로 생성한 값이라는 전제 하의 가벼운 형식 가드 —
 * 의도적으로 strict 하지 않게 하여 generator 호환성을 깨지 않는다.
 *
 * <p>{@link #randomUuidV4()} 헬퍼는 test fixture / k6 시나리오 자바 변환 / 운영 스크립트가 hex 를
 * 직접 hand-craft 하지 않도록 제공한다. {@link UUID#randomUUID()} 결과는 v4 임이 JDK 명세로 보장되며
 * {@link UUID#toString()} 형식이 {@link #UUID_V4} regex 와 정확히 매치한다 (회귀 가드는
 * {@code SessionIdPatternsTest}).
 */
public final class SessionIdPatterns {

    /** UUIDv4 형식 (소문자 hex 8-4-4-4-12). */
    public static final String UUID_V4 = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    /** {@code @Pattern.message} 공통 문구 — 검증 실패 응답에서 client 가 일관되게 인지. */
    public static final String UUID_V4_MESSAGE = "UUIDv4 format required";

    /** 컴파일된 패턴 — {@link #randomUuidV4()} 자가 검증 및 외부 일회 검증 용. */
    private static final Pattern UUID_V4_PATTERN = Pattern.compile(UUID_V4);

    private SessionIdPatterns() {
        // 상수 유틸 — 인스턴스화 금지
    }

    /**
     * 새 UUIDv4 sessionId 를 생성한다. test fixture / k6 헬퍼 자바 포팅 / 운영 스크립트가 hex 를 직접
     * 손으로 작성하지 않도록 제공하는 단일 진입점.
     *
     * <p>{@link UUID#randomUUID()} 는 JDK 명세상 RFC 4122 v4 UUID 를 반환하며, {@link UUID#toString()}
     * 결과는 항상 소문자 hex 8-4-4-4-12 — {@link #UUID_V4} regex 와 정확히 매치한다. v4 의 version/variant
     * nibble 까지 검증하진 않으나 (regex 가 그 수준이 아님), 본 헬퍼 출력은 정의상 항상 통과한다.
     *
     * @return UUIDv4 형식의 sessionId 문자열
     */
    public static String randomUuidV4() {
        return UUID.randomUUID().toString();
    }

    /**
     * 주어진 문자열이 {@link #UUID_V4} 형식인지 검사한다. DTO {@code @Pattern} 검증과 결과가 동일하나
     * 어노테이션 외부 (스크립트 / 마이그레이션 검증 / 테스트 단정) 에서 재사용 가능하도록 제공한다.
     *
     * @param sessionId 검사할 sessionId 값. null 은 {@code false} 로 처리 — {@code @NotBlank} 책임은 caller.
     * @return 형식 일치 여부
     */
    public static boolean matches(final String sessionId) {
        if (sessionId == null) {
            return false;
        }
        return UUID_V4_PATTERN.matcher(sessionId).matches();
    }
}
