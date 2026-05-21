package com.mobruji.integration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
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

/**
 * GET /api/v1/sessions/{sessionId}/voice-range-history E2E (PR C of #220).
 *
 * <p>spec: docs/features/voice-range-progress.md §5-2, §7. 같은 sessionId로 측정 3회 누적 → 시계열을 measuredAt 오름차순으로 조회.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class VoiceRangeHistoryIntegrationTest {

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
    @DisplayName("E2E: 같은 sessionId로 측정 3회 → history 3건을 measuredAt ASC 로 응답")
    void e2e_history_returnsThreeSnapshotsInAscOrder() {
        final String sessionId = "e2e-history-asc";

        // given: 3회 측정 (POST 1회 + PUT 2회) — voice_range 1건 + snapshot 3건 누적
        postVoiceRange(sessionId, 48, 69, "OCTAVE_PICK");
        putVoiceRange(sessionId, 50, 72, "MIC_MEASURE");
        putVoiceRange(sessionId, 52, 74, "SELF_REPORT");

        // when / then
        given()
                .when()
                .get("/api/v1/sessions/{sessionId}/voice-range-history", sessionId)
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("voiceRangeSnapshotResponses", hasSize(3))
                .body("voiceRangeSnapshotResponses[0].lowMidi", equalTo(48))
                .body("voiceRangeSnapshotResponses[0].highMidi", equalTo(69))
                .body("voiceRangeSnapshotResponses[0].lowestNoteName", equalTo("C3"))
                .body("voiceRangeSnapshotResponses[0].highestNoteName", equalTo("A4"))
                .body("voiceRangeSnapshotResponses[0].sourceMethod", equalTo("OCTAVE_PICK"))
                .body("voiceRangeSnapshotResponses[0].measuredAt", notNullValue())
                .body("voiceRangeSnapshotResponses[0].id", notNullValue())
                .body("voiceRangeSnapshotResponses[1].lowMidi", equalTo(50))
                .body("voiceRangeSnapshotResponses[1].highMidi", equalTo(72))
                .body("voiceRangeSnapshotResponses[1].sourceMethod", equalTo("MIC_MEASURE"))
                .body("voiceRangeSnapshotResponses[2].lowMidi", equalTo(52))
                .body("voiceRangeSnapshotResponses[2].highMidi", equalTo(74))
                .body("voiceRangeSnapshotResponses[2].sourceMethod", equalTo("SELF_REPORT"));
    }

    @Test
    @DisplayName("E2E: snapshot 없는 sessionId → 200 + 빈 배열")
    void e2e_history_unknownSession_returnsEmptyList() {
        given()
                .when()
                .get("/api/v1/sessions/{sessionId}/voice-range-history", "no-such-session")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("voiceRangeSnapshotResponses", hasSize(0));
    }

    @Test
    @DisplayName("E2E: 다른 sessionId의 snapshot은 응답에 포함되지 않는다")
    void e2e_history_isolatedBySessionId() {
        postVoiceRange("session-A", 48, 69, "OCTAVE_PICK");
        postVoiceRange("session-B", 55, 78, "SELF_REPORT");

        given()
                .when()
                .get("/api/v1/sessions/{sessionId}/voice-range-history", "session-A")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("voiceRangeSnapshotResponses", hasSize(1))
                .body("voiceRangeSnapshotResponses[0].lowMidi", equalTo(48));
    }

    private void postVoiceRange(final String sessionId, final int lowMidi, final int highMidi,
            final String sourceMethod) {
        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body("""
                        {"sessionId":"%s","lowestNoteMidi":%d,"highestNoteMidi":%d,"sourceMethod":"%s"}
                        """.formatted(sessionId, lowMidi, highMidi, sourceMethod))
                .when()
                .post("/api/v1/voice-ranges")
                .then()
                .statusCode(HttpStatus.CREATED.value());
    }

    private void putVoiceRange(final String sessionId, final int lowMidi, final int highMidi,
            final String sourceMethod) {
        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body("""
                        {"lowestNoteMidi":%d,"highestNoteMidi":%d,"sourceMethod":"%s"}
                        """.formatted(lowMidi, highMidi, sourceMethod))
                .when()
                .put("/api/v1/voice-ranges/" + sessionId)
                .then()
                .statusCode(HttpStatus.OK.value());
    }
}
