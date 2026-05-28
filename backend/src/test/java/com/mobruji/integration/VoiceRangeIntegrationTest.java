package com.mobruji.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import com.mobruji.voice.infrastructure.VoiceRangeRepository;
import com.mobruji.voice.infrastructure.VoiceRangeSnapshotRepository;

import io.restassured.RestAssured;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class VoiceRangeIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private VoiceRangeRepository voiceRangeRepository;

    @Autowired
    private VoiceRangeSnapshotRepository voiceRangeSnapshotRepository;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        voiceRangeSnapshotRepository.deleteAll();
        voiceRangeRepository.deleteAll();
    }

    @Test
    @DisplayName("E2E: POST → GET → PUT roundtrip 성공 케이스")
    void e2e_createReadUpdate_succeeds() {
        // #948: VoiceRangeCreateRequest.sessionId @Pattern(UUIDv4) 적용 — UUIDv4 fixture 필수.
        final String sessionId = "550e8400-e29b-41d4-a716-446655448001";
        final String createBody = """
                {
                  "sessionId": "%s",
                  "lowestNoteMidi": 48,
                  "highestNoteMidi": 69,
                  "sourceMethod": "OCTAVE_PICK"
                }
                """.formatted(sessionId);

        // 1) POST → 201
        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .header("X-Session-Id", sessionId)
                .body(createBody)
                .when()
                .post("/api/v1/voice-ranges")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .body("id", notNullValue())
                .body("sessionId", equalTo(sessionId))
                .body("lowestNoteMidi", equalTo(48))
                .body("highestNoteMidi", equalTo(69))
                .body("sourceMethod", equalTo("OCTAVE_PICK"));

        // 2) GET → 200
        given()
                .header("X-Session-Id", sessionId)
                .when()
                .get("/api/v1/voice-ranges/" + sessionId)
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("sessionId", equalTo(sessionId))
                .body("lowestNoteMidi", equalTo(48));

        // 3) PUT → 200, 갱신 반영
        final String updateBody = """
                {
                  "lowestNoteMidi": 50,
                  "highestNoteMidi": 72,
                  "sourceMethod": "MIC_MEASURE"
                }
                """;
        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .header("X-Session-Id", sessionId)
                .body(updateBody)
                .when()
                .put("/api/v1/voice-ranges/" + sessionId)
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("lowestNoteMidi", equalTo(50))
                .body("highestNoteMidi", equalTo(72))
                .body("sourceMethod", equalTo("MIC_MEASURE"));

        // 4) GET 재조회 → 갱신 확인
        given()
                .header("X-Session-Id", sessionId)
                .when()
                .get("/api/v1/voice-ranges/" + sessionId)
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("lowestNoteMidi", equalTo(50));
    }

    @Test
    @DisplayName("E2E: 같은 sessionId로 POST 2번 → 두 번째는 덮어쓰기 (Q4 최신 1건)")
    void e2e_postTwice_replacesExisting() {
        final String sessionId = "550e8400-e29b-41d4-a716-446655448002";

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .header("X-Session-Id", sessionId)
                .body("""
                        {"sessionId":"%s","lowestNoteMidi":48,"highestNoteMidi":69,"sourceMethod":"OCTAVE_PICK"}
                        """.formatted(sessionId))
                .when()
                .post("/api/v1/voice-ranges")
                .then()
                .statusCode(HttpStatus.CREATED.value());

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .header("X-Session-Id", sessionId)
                .body("""
                        {"sessionId":"%s","lowestNoteMidi":55,"highestNoteMidi":78,"sourceMethod":"SELF_REPORT"}
                        """.formatted(sessionId))
                .when()
                .post("/api/v1/voice-ranges")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .body("lowestNoteMidi", equalTo(55))
                .body("sourceMethod", equalTo("SELF_REPORT"));

        // voice_range 는 1행(최신값으로 덮어쓰기) 이지만 snapshot 은 2행 누적 (insert-only)
        assertThat(voiceRangeRepository.findBySessionId(sessionId)).isPresent();
        assertThat(voiceRangeSnapshotRepository.findBySessionIdOrderByMeasuredAtDesc(sessionId)).hasSize(2);
    }

    @Test
    @DisplayName("E2E: POST 시 voice_range upsert 와 동일 트랜잭션에서 snapshot 1행 insert")
    void e2e_post_persistsSnapshotAlongsideVoiceRange() {
        final String sessionId = "550e8400-e29b-41d4-a716-446655448003";

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .header("X-Session-Id", sessionId)
                .body("""
                        {"sessionId":"%s","lowestNoteMidi":48,"highestNoteMidi":69,"sourceMethod":"OCTAVE_PICK"}
                        """.formatted(sessionId))
                .when()
                .post("/api/v1/voice-ranges")
                .then()
                .statusCode(HttpStatus.CREATED.value());

        final var voiceRange = voiceRangeRepository.findBySessionId(sessionId).orElseThrow();
        final var voiceRangeSnapshotResponses = voiceRangeSnapshotRepository.findBySessionIdOrderByMeasuredAtDesc(
                sessionId);
        assertThat(voiceRangeSnapshotResponses).hasSize(1);
        assertThat(voiceRangeSnapshotResponses.get(0).getSessionId()).isEqualTo(sessionId);
        assertThat(voiceRangeSnapshotResponses.get(0).getLowMidi()).isEqualTo(voiceRange.getLowestNoteMidi());
        assertThat(voiceRangeSnapshotResponses.get(0).getHighMidi()).isEqualTo(voiceRange.getHighestNoteMidi());
        assertThat(voiceRangeSnapshotResponses.get(0).getSourceMethod()).isEqualTo(voiceRange.getSourceMethod());
        assertThat(voiceRangeSnapshotResponses.get(0).getMeasuredAt()).isNotNull();
    }

    @Test
    @DisplayName("E2E: PUT update 시에도 snapshot 1행 추가 누적")
    void e2e_putUpdate_appendsSnapshot() {
        final String sessionId = "550e8400-e29b-41d4-a716-446655448004";
        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .header("X-Session-Id", sessionId)
                .body("""
                        {"sessionId":"%s","lowestNoteMidi":48,"highestNoteMidi":69,"sourceMethod":"OCTAVE_PICK"}
                        """.formatted(sessionId))
                .when()
                .post("/api/v1/voice-ranges")
                .then()
                .statusCode(HttpStatus.CREATED.value());

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .header("X-Session-Id", sessionId)
                .body("""
                        {"lowestNoteMidi":50,"highestNoteMidi":72,"sourceMethod":"MIC_MEASURE"}
                        """)
                .when()
                .put("/api/v1/voice-ranges/" + sessionId)
                .then()
                .statusCode(HttpStatus.OK.value());

        final var voiceRangeSnapshotResponses = voiceRangeSnapshotRepository.findBySessionIdOrderByMeasuredAtAsc(
                sessionId);
        assertThat(voiceRangeSnapshotResponses).hasSize(2);
        assertThat(voiceRangeSnapshotResponses.get(0).getLowMidi()).isEqualTo(48);
        assertThat(voiceRangeSnapshotResponses.get(1).getLowMidi()).isEqualTo(50);
        assertThat(voiceRangeSnapshotResponses.get(1).getSourceMethod())
                .isEqualTo(com.mobruji.voice.domain.VoiceRangeSourceMethod.MIC_MEASURE);
    }

    /**
     * 이슈 #868 — ADR-0011 §28 후속 적용. POST/GET/PUT 모두 session-bound 이므로
     * {@code X-Session-Id} 헤더가 누락/blank/path-or-body 와 불일치 시 401 이어야 한다.
     * sessionId 원문은 응답에 노출되지 않는다 (security-policy.md §3).
     */
    @Nested
    @DisplayName("session-bound 인증 (ADR-0011 §28, 이슈 #868)")
    class SessionBoundAuth {

        @Test
        @DisplayName("GET: X-Session-Id 헤더 누락 → 401")
        void get_missingHeader_returns401() {
            final String sessionId = "e2e-auth-get-missing";

            given()
                    .when()
                    .get("/api/v1/voice-ranges/" + sessionId)
                    .then()
                    .statusCode(HttpStatus.UNAUTHORIZED.value());
        }

        @Test
        @DisplayName("GET: X-Session-Id 헤더 blank → 401")
        void get_blankHeader_returns401() {
            final String sessionId = "e2e-auth-get-blank";

            given()
                    .header("X-Session-Id", "   ")
                    .when()
                    .get("/api/v1/voice-ranges/" + sessionId)
                    .then()
                    .statusCode(HttpStatus.UNAUTHORIZED.value());
        }

        @Test
        @DisplayName("GET: X-Session-Id 헤더와 path sessionId 불일치 → 401, 음역 원문 미노출")
        void get_mismatchedHeader_returns401() {
            final String sessionA = "e2e-auth-get-A";
            final String sessionB = "e2e-auth-get-B";

            given()
                    .header("X-Session-Id", sessionB)
                    .when()
                    .get("/api/v1/voice-ranges/" + sessionA)
                    .then()
                    .statusCode(HttpStatus.UNAUTHORIZED.value())
                    // 401 응답에 sessionId 원문이 노출되지 않아야 함
                    .body("message", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(sessionA)));
        }

        @Test
        @DisplayName("PUT: X-Session-Id 헤더 누락 → 401 (음역 갱신 차단)")
        void put_missingHeader_returns401() {
            final String sessionId = "e2e-auth-put-missing";

            given()
                    .contentType(MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"lowestNoteMidi":50,"highestNoteMidi":72,"sourceMethod":"MIC_MEASURE"}
                            """)
                    .when()
                    .put("/api/v1/voice-ranges/" + sessionId)
                    .then()
                    .statusCode(HttpStatus.UNAUTHORIZED.value());
            // 가드가 service 전에 거부 → 음역이 생성/수정되지 않아야 함
            assertThat(voiceRangeRepository.findBySessionId(sessionId)).isEmpty();
        }

        @Test
        @DisplayName("PUT: X-Session-Id 헤더와 path sessionId 불일치 → 401 (음역 갱신 차단)")
        void put_mismatchedHeader_returns401() {
            final String pathSessionId = "e2e-auth-put-A";
            final String headerSessionId = "e2e-auth-put-B";

            given()
                    .contentType(MediaType.APPLICATION_JSON_VALUE)
                    .header("X-Session-Id", headerSessionId)
                    .body("""
                            {"lowestNoteMidi":50,"highestNoteMidi":72,"sourceMethod":"MIC_MEASURE"}
                            """)
                    .when()
                    .put("/api/v1/voice-ranges/" + pathSessionId)
                    .then()
                    .statusCode(HttpStatus.UNAUTHORIZED.value());
            assertThat(voiceRangeRepository.findBySessionId(pathSessionId)).isEmpty();
            assertThat(voiceRangeRepository.findBySessionId(headerSessionId)).isEmpty();
        }

        @Test
        @DisplayName("POST: X-Session-Id 헤더 누락 → 401 (upsert 실행 차단)")
        void post_missingHeader_returns401() {
            // body sessionId 는 UUIDv4 (#948) — 헤더 누락이 인증 게이트 차단 사유여야 함.
            // 비-UUIDv4 라면 @Pattern 가 먼저 400 을 던져 인증 차단 검증이 깨진다.
            final String sessionId = "550e8400-e29b-41d4-a716-446655448901";
            final String requestBody = """
                    {"sessionId":"%s","lowestNoteMidi":48,"highestNoteMidi":69,"sourceMethod":"OCTAVE_PICK"}
                    """.formatted(sessionId);

            given()
                    .contentType(MediaType.APPLICATION_JSON_VALUE)
                    .body(requestBody)
                    .when()
                    .post("/api/v1/voice-ranges")
                    .then()
                    .statusCode(HttpStatus.UNAUTHORIZED.value());
            assertThat(voiceRangeRepository.findBySessionId(sessionId)).isEmpty();
            assertThat(voiceRangeSnapshotRepository.findBySessionIdOrderByMeasuredAtDesc(sessionId)).isEmpty();
        }

        @Test
        @DisplayName("POST: body sessionId ≠ X-Session-Id 헤더 → 401 (음역 위조 차단)")
        void post_mismatchedHeader_returns401() {
            // 둘 다 UUIDv4 (#948) — header/body 불일치가 인증 게이트 차단 사유여야 함.
            final String bodySessionId = "550e8400-e29b-41d4-a716-446655448902";
            final String headerSessionId = "550e8400-e29b-41d4-a716-446655448903";
            final String requestBody = """
                    {"sessionId":"%s","lowestNoteMidi":48,"highestNoteMidi":69,"sourceMethod":"OCTAVE_PICK"}
                    """.formatted(bodySessionId);

            given()
                    .contentType(MediaType.APPLICATION_JSON_VALUE)
                    .header("X-Session-Id", headerSessionId)
                    .body(requestBody)
                    .when()
                    .post("/api/v1/voice-ranges")
                    .then()
                    .statusCode(HttpStatus.UNAUTHORIZED.value());
            assertThat(voiceRangeRepository.findBySessionId(bodySessionId)).isEmpty();
            assertThat(voiceRangeRepository.findBySessionId(headerSessionId)).isEmpty();
        }
    }
}
