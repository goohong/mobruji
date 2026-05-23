package com.mobruji.user.api.dto;

/**
 * 회전/만료 시 데이터 처리 모드.
 *
 * <p>spec: {@code docs/features/anonymous-session-lifecycle.md} §5-4, ADR-0013 §D-3.
 *
 * <ul>
 * <li>{@link #DELETE}: cascade-delete (default, v0.3 유일 지원).</li>
 * <li>{@link #ANONYMIZE}: 익명 aggregate 보존 — v0.4 후속 spec 에서 활성화. v0.3 에서 요청 시
 * 400 반환.</li>
 * </ul>
 */
public enum SessionDataMode {
    DELETE,
    ANONYMIZE
}
