package com.mobruji.integration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import io.restassured.RestAssured;

/**
 * E2E: CORS preflight 정책 검증 (rev 21 QA, #314).
 *
 * <p>spec: {@code docs/features/voice-range-input.md} — Next.js dev 서버
 * ({@code http://localhost:3000}) 가 백엔드 호출 시 preflight 403 으로 막혔던 회귀를 차단.
 *
 * <ul>
 * <li>허용 origin 의 preflight {@code OPTIONS} → 200 + {@code Access-Control-Allow-Origin} 헤더 echo</li>
 * <li>허용되지 않은 origin → 403 ({@code Invalid CORS request})</li>
 * <li>preflight 통과 후 실제 {@code POST} 본요청 → 정상 응답 + ACAO 헤더 포함 (인증 가드와 충돌 없음)</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CorsPreflightIntegrationTest {

    // application-test.yml 의 mobruji.cors.allowed-origins 와 동일해야 한다.
    private static final String ALLOWED_DEV_ORIGIN = "http://localhost:3000";
    private static final String DISALLOWED_ORIGIN = "http://evil.example";

    private static final String ACAO_HEADER = "Access-Control-Allow-Origin";
    private static final String ACAM_HEADER = "Access-Control-Allow-Methods";

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    @DisplayName("preflight: 허용 origin → 200 + ACAO 헤더 echo")
    void preflightAllowedOriginReturns200WithAcaoHeader() {
        given()
                .header("Origin", ALLOWED_DEV_ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type,X-Session-Id")
                .when()
                .options("/api/v1/voice-ranges")
                .then()
                .statusCode(HttpStatus.OK.value())
                .header(ACAO_HEADER, equalTo(ALLOWED_DEV_ORIGIN))
                .header(ACAM_HEADER, notNullValue());
    }

    @Test
    @DisplayName("preflight: 허용되지 않은 origin → 403 (Invalid CORS request)")
    void preflightDisallowedOriginReturns403() {
        given()
                .header("Origin", DISALLOWED_ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .when()
                .options("/api/v1/voice-ranges")
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    @DisplayName("실 요청: 허용 origin 의 POST → ACAO 헤더 echo (인증/검증 가드와 충돌 없음)")
    void actualRequestAllowedOriginEchoesAcao() {
        // body 형식이 틀려도 (4xx) framework 는 CORS 헤더를 응답에 부착해야 한다.
        // 본 테스트의 관심사는 "ACAO 가 실제 응답에 포함되는가" 이며, body 유효성은 별도 E2E 가 다룬다.
        given()
                .header("Origin", ALLOWED_DEV_ORIGIN)
                .header("Content-Type", "application/json")
                .body("{}")
                .when()
                .post("/api/v1/voice-ranges")
                .then()
                .header(ACAO_HEADER, equalTo(ALLOWED_DEV_ORIGIN));
    }
}
