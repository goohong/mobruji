package com.mobruji.web;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotEmpty;

/**
 * 브라우저 cross-origin 요청 허용 정책 설정.
 *
 * <p>spec: docs/features/voice-range-input.md / rev 21 QA(#314) — Next.js dev origin
 * {@code http://localhost:3000} 에서 백엔드 호출 시 preflight 403 차단 해소.
 *
 * <ul>
 * <li>{@code allowedOrigins}: 허용할 Origin 목록 (정확 매칭). 환경변수
 * {@code MOBRUJI_CORS_ALLOWED_ORIGINS} 로 쉼표 구분 주입. 비어 있으면 부팅 fail-fast
 * — 의도치 않은 전체 차단 또는 와일드카드 노출을 방지.</li>
 * </ul>
 *
 * <p>현재 인증은 {@code X-Session-Id}/{@code X-Admin-Token} 헤더 방식이며 쿠키를 사용하지
 * 않으므로 {@code allowCredentials=false} 로 고정한다 (별도 설정 노출하지 않음).
 *
 * <p>운영 원칙: 본 설정은 보호 영역({@code application.yml}). 허용 origin 변경은
 * needs-human-review 라벨 PR 로 가시화한다.
 */
@Validated
@ConfigurationProperties(prefix = "mobruji.cors")
public record CorsProperties(
        @NotEmpty List<String> allowedOrigins
) {
}
