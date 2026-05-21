package com.mobruji.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
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
        final String sessionId = "e2e-session-1";
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
                .when()
                .get("/api/v1/voice-ranges/" + sessionId)
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("lowestNoteMidi", equalTo(50));
    }

    @Test
    @DisplayName("E2E: 같은 sessionId로 POST 2번 → 두 번째는 덮어쓰기 (Q4 최신 1건)")
    void e2e_postTwice_replacesExisting() {
        final String sessionId = "e2e-session-2";

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body("""
                        {"sessionId":"%s","lowestNoteMidi":48,"highestNoteMidi":69,"sourceMethod":"OCTAVE_PICK"}
                        """.formatted(sessionId))
                .when()
                .post("/api/v1/voice-ranges")
                .then()
                .statusCode(HttpStatus.CREATED.value());

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
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
        final String sessionId = "e2e-snapshot-session";

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
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
        final String sessionId = "e2e-snapshot-put";
        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body("""
                        {"sessionId":"%s","lowestNoteMidi":48,"highestNoteMidi":69,"sourceMethod":"OCTAVE_PICK"}
                        """.formatted(sessionId))
                .when()
                .post("/api/v1/voice-ranges")
                .then()
                .statusCode(HttpStatus.CREATED.value());

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
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
}
