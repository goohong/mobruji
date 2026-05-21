package com.mobruji.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/**
 * Admin 전용 endpoint 보호용 임시 토큰 설정 (v0.3 P0).
 *
 * <p>spec: docs/ai-harness/04-security-policy.md — admin endpoint 무인증 금지 (rev 15 #224/#228).
 *
 * <p>Spring Security 정식 도입 전까지의 임시 게이트. 단일 공유 토큰을 HTTP 헤더
 * {@code X-Admin-Token} 으로 받아 검증한다.
 *
 * <ul>
 * <li>{@code token}: 환경변수 {@code MOBRUJI_ADMIN_TOKEN} 으로 주입 (yml에 평문 금지).
 * blank/null 이면 부팅 fail-fast — admin endpoint를 보호 없이 노출하지 않기 위함.</li>
 * </ul>
 *
 * <p>운영 원칙: 토큰은 로그/예외 메시지/응답에 절대 노출하지 않는다 (마스킹).
 * 본 PR은 임시 조치이며, v0.3 후속 PR에서 Spring Security + 역할 기반 인가로 대체한다.
 */
@Validated
@ConfigurationProperties(prefix = "mobruji.admin")
public record AdminAuthProperties(
        @NotBlank String token
) {
}
