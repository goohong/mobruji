package com.mobruji.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import com.mobruji.user.domain.AnonymousSession;
import com.mobruji.user.domain.RevokedReason;
import com.mobruji.user.infrastructure.AnonymousSessionRepository;

import io.restassured.RestAssured;

/**
 * E2E: {@code POST /api/v1/sessions/rotate}.
 *
 * <p>spec: {@code docs/features/anonymous-session-lifecycle.md} §5-4 / §7, ADR-0013 §D-2.
 * CLAUDE.md §4 — 신규 endpoint 는 RestAssured E2E 성공 케이스 필수.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SessionRotateIntegrationTest {

    private static final String UUID_REGEX = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    @LocalServerPort
    private int port;

    @Autowired
    private AnonymousSessionRepository anonymousSessionRepository;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        anonymousSessionRepository.deleteAll();
    }

    @Test
    @DisplayName("E2E: rotate 성공 — 새 UUIDv4 발급 + 기존 sessionId revoked(USER_ROTATE)")
    void e2e_rotate_success_returnsNewSessionId() {
        // given: bootstrap-on-rotate — 기존 행 없이도 동작 (spec §5-5-1 옵션 a)
        final String currentSessionId = "550e8400-e29b-41d4-a716-446655440001";

        // when / then
        final String newSessionId = given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .header("X-Session-Id", currentSessionId)
                .body("""
                        {"currentSessionId":"%s"}
                        """.formatted(currentSessionId))
                .when()
                .post("/api/v1/sessions/rotate")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("newSessionId", notNullValue())
                .body("newSessionId", matchesPattern(UUID_REGEX))
                .extract().path("newSessionId");

        // then: AnonymousSession 영속 검증
        final AnonymousSession revokedOldSession = anonymousSessionRepository.findById(currentSessionId)
                .orElseThrow();
        assertThat(revokedOldSession.isRevoked()).isTrue();
        assertThat(revokedOldSession.getRevokedReason()).isEqualTo(RevokedReason.USER_ROTATE);

        final AnonymousSession newSession = anonymousSessionRepository.findById(newSessionId).orElseThrow();
        assertThat(newSession.isRevoked()).isFalse();
        assertThat(newSession.getSessionId()).isNotEqualTo(currentSessionId);
    }

    @Test
    @DisplayName("E2E: rotate — dataMode=DELETE 명시도 동일하게 동작")
    void e2e_rotate_withExplicitDeleteMode_returnsNewSessionId() {
        final String currentSessionId = "550e8400-e29b-41d4-a716-446655440002";

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .header("X-Session-Id", currentSessionId)
                .body("""
                        {"currentSessionId":"%s","dataMode":"DELETE"}
                        """.formatted(currentSessionId))
                .when()
                .post("/api/v1/sessions/rotate")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("newSessionId", matchesPattern(UUID_REGEX));
    }

    @Test
    @DisplayName("E2E: rotate — dataMode=ANONYMIZE 는 v0.3 미지원 → 400")
    void e2e_rotate_withAnonymizeMode_returns400() {
        final String currentSessionId = "550e8400-e29b-41d4-a716-446655440003";

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .header("X-Session-Id", currentSessionId)
                .body("""
                        {"currentSessionId":"%s","dataMode":"ANONYMIZE"}
                        """.formatted(currentSessionId))
                .when()
                .post("/api/v1/sessions/rotate")
                .then()
                .statusCode(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    @DisplayName("E2E: rotate — X-Session-Id 헤더 누락 → 401")
    void e2e_rotate_missingHeader_returns401() {
        final String currentSessionId = "550e8400-e29b-41d4-a716-446655440004";

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body("""
                        {"currentSessionId":"%s"}
                        """.formatted(currentSessionId))
                .when()
                .post("/api/v1/sessions/rotate")
                .then()
                .statusCode(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    @DisplayName("E2E: rotate — 헤더와 body sessionId 불일치 → 401")
    void e2e_rotate_mismatchedHeader_returns401() {
        final String currentSessionId = "550e8400-e29b-41d4-a716-446655440005";

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .header("X-Session-Id", "different-session-id")
                .body("""
                        {"currentSessionId":"%s"}
                        """.formatted(currentSessionId))
                .when()
                .post("/api/v1/sessions/rotate")
                .then()
                .statusCode(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    @DisplayName("E2E: rotate — body currentSessionId blank → 400")
    void e2e_rotate_blankBody_returns400() {
        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .header("X-Session-Id", "any")
                .body("""
                        {"currentSessionId":""}
                        """)
                .when()
                .post("/api/v1/sessions/rotate")
                .then()
                .statusCode(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    @DisplayName("E2E: rotate — body currentSessionId non-UUIDv4 형식 → 400 (F5 보안 보강)")
    void e2e_rotate_nonUuidBody_returns400() {
        final String invalidSessionId = "not-a-uuid-random-string-12345";

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .header("X-Session-Id", invalidSessionId)
                .body("""
                        {"currentSessionId":"%s"}
                        """.formatted(invalidSessionId))
                .when()
                .post("/api/v1/sessions/rotate")
                .then()
                .statusCode(HttpStatus.BAD_REQUEST.value());
    }
}
