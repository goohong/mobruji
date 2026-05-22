package com.mobruji.web;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import lombok.RequiredArgsConstructor;

/**
 * 전역 CORS 매핑 등록 ({@code WebMvcConfigurer} 기반).
 *
 * <p>spec: rev 21 QA(#314) — Spring Security 미도입 단계의 단순 케이스이며,
 * {@link CorsProperties#allowedOrigins()} 에 정의된 origin 에 한해 모든 경로 cross-origin 호출을
 * 허용한다. preflight(OPTIONS) 는 컨트롤러 진입 전 framework 가 응답하므로
 * {@code SessionAuthGuard}/{@code AdminTokenVerifier} 와 충돌하지 않는다 (헤더 검사는 본요청 시점).
 *
 * <ul>
 * <li>methods: GET POST PUT PATCH DELETE OPTIONS</li>
 * <li>allowedHeaders: 모든 헤더 허용 (브라우저가 전송하는 {@code X-Session-Id} /
 * {@code X-Admin-Token} / {@code Content-Type} / {@code Accept} 등을 명시 화이트리스트로 묶지
 * 않는다 — 추가 헤더가 늘어날 때마다 보호 영역 PR 을 만드는 비용을 피한다).</li>
 * <li>exposedHeaders: {@code Location} — 향후 201 Created 응답의 리소스 위치 헤더를 브라우저가
 * 읽을 수 있도록 노출.</li>
 * <li>allowCredentials: {@code false} — 현재 cookie 미사용 (헤더 기반 세션).
 * {@code true} 로 바꿀 경우 와일드카드 origin 사용이 금지되며 별도 ADR 필요.</li>
 * <li>maxAge: 3600s — preflight 결과 캐시.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(CorsProperties.class)
@RequiredArgsConstructor
public class WebCorsConfig implements WebMvcConfigurer {

    private static final long PREFLIGHT_MAX_AGE_SECONDS = 3600L;

    private final CorsProperties corsProperties;

    @Override
    public void addCorsMappings(final CorsRegistry corsRegistry) {
        corsRegistry.addMapping("/**")
                .allowedOrigins(corsProperties.allowedOrigins().toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Location")
                .allowCredentials(false)
                .maxAge(PREFLIGHT_MAX_AGE_SECONDS);
    }
}
