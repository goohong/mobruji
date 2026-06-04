package com.mobruji.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import com.mobruji.recommendation.infrastructure.RecommendationRepository;
import com.mobruji.recommendation.infrastructure.RecommendationRequestRepository;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.Mood;
import com.mobruji.recommendation.infrastructure.MusicalKeyMidiResolver;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

import io.restassured.RestAssured;

/**
 * GET /api/v1/sessions/{sessionId}/recommendation-history E2E (spec PR C of #236).
 *
 * <p>spec: docs/features/recommendation-history-and-feedback.md §5-2, §7.
 * 같은 sessionId 로 POST /recommendations 를 N회 호출 → 응답에 N건이 최신순으로 노출.
 * 다른 세션의 추천은 격리.
 *
 * <p>rev 16(#238): {@code X-Session-Id} 헤더 인증 게이트. path sessionId 와 일치하는 헤더 필수.
 * 누락/불일치 → 401.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RecommendationHistoryIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private SongRepository songRepository;

    @Autowired
    private RecommendationRequestRepository recommendationRequestRepository;

    @Autowired
    private RecommendationRepository recommendationRepository;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        recommendationRepository.deleteAll();
        recommendationRequestRepository.deleteAll();
        songRepository.deleteAll();
        songRepository.save(buildSong("벚꽃 엔딩", "버스커 버스커", MusicalKey.A_MAJOR, Mood.EMOTIONAL, "발라드"));
        songRepository.save(buildSong("Dynamite", "BTS", MusicalKey.E_MAJOR, Mood.UPBEAT, "댄스"));
        songRepository.save(buildSong("Spring Day", "BTS", MusicalKey.D_SHARP_MAJOR, Mood.EMOTIONAL, "발라드"));
        songRepository.save(buildSong("Eight", "IU", MusicalKey.A_MAJOR, Mood.NOSTALGIC, "팝"));
        songRepository.save(buildSong("Lilac", "IU", MusicalKey.D_MAJOR, Mood.UPBEAT, "팝"));
    }

    @Test
    @DisplayName("E2E: 같은 sessionId 로 추천 2회 → history 응답에 2건이 최신순으로 노출")
    void e2e_history_returnsTwoRequestsInDescOrder() {
        // UUIDv4 (#948)
        final String sessionId = "550e8400-e29b-41d4-a716-11eeeec01a01";
        // given: 추천 2회 (서로 다른 입력으로 별도 row 생성)
        final Integer firstRequestId = postRecommendation(sessionId, 55, 75, "UPBEAT");
        final Integer secondRequestId = postRecommendation(sessionId, 50, 72, "EMOTIONAL");

        // when / then: 최신(두 번째) 요청이 [0] 에 위치
        given()
                .header("X-Session-Id", sessionId)
                .when()
                .get("/api/v1/sessions/{sessionId}/recommendation-history", sessionId)
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("recommendationHistoryResponses", hasSize(2))
                .body("recommendationHistoryResponses[0].requestId", equalTo(secondRequestId))
                // #423 (closes): SessionAuthGuard 가 일치 검증하므로 응답 echo 제거 — 키 자체가 없어야 함
                .body("recommendationHistoryResponses[0].sessionId", nullValue())
                .body("recommendationHistoryResponses[0].voiceRangeLow", equalTo(50))
                .body("recommendationHistoryResponses[0].voiceRangeHigh", equalTo(72))
                .body("recommendationHistoryResponses[0].mood", equalTo("EMOTIONAL"))
                .body("recommendationHistoryResponses[0].requestedAt", notNullValue())
                .body("recommendationHistoryResponses[0].recommendations.size()", greaterThan(0))
                .body("recommendationHistoryResponses[0].recommendations[0].rankPosition", equalTo(1))
                .body("recommendationHistoryResponses[0].recommendations[0].matchReason", notNullValue())
                .body("recommendationHistoryResponses[0].recommendations[0].song.title", notNullValue())
                .body("recommendationHistoryResponses[1].requestId", equalTo(firstRequestId))
                .body("recommendationHistoryResponses[1].voiceRangeLow", equalTo(55))
                .body("recommendationHistoryResponses[1].voiceRangeHigh", equalTo(75))
                .body("recommendationHistoryResponses[1].mood", equalTo("UPBEAT"));
    }

    @Test
    @DisplayName("E2E: 추천 이력 없는 sessionId → 200 + 빈 배열 (인증 통과 시)")
    void e2e_history_unknownSession_returnsEmptyList() {
        // UUIDv4 (#948) — POST 가드 없이 GET 만 호출. path 와 header 동일하면 인증 통과.
        final String unknownSessionId = "550e8400-e29b-41d4-a716-11eeeec01a02";
        given()
                .header("X-Session-Id", unknownSessionId)
                .when()
                .get("/api/v1/sessions/{sessionId}/recommendation-history", unknownSessionId)
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("recommendationHistoryResponses", hasSize(0));
    }

    @Test
    @DisplayName("E2E: 다른 sessionId 의 추천은 응답에 포함되지 않는다")
    void e2e_history_isolatedBySessionId() {
        // UUIDv4 (#948)
        final String sessionA = "550e8400-e29b-41d4-a716-11eeeec01a03";
        final String sessionB = "550e8400-e29b-41d4-a716-11eeeec01a04";
        postRecommendation(sessionA, 55, 75, "UPBEAT");
        postRecommendation(sessionB, 50, 80, "EMOTIONAL");

        given()
                .header("X-Session-Id", sessionA)
                .when()
                .get("/api/v1/sessions/{sessionId}/recommendation-history", sessionA)
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("recommendationHistoryResponses", hasSize(1))
                // #423 (closes): sessionId echo 제거 — 격리는 path/header 인증으로 보장됨
                .body("recommendationHistoryResponses[0].sessionId", nullValue())
                .body("recommendationHistoryResponses[0].mood", equalTo("UPBEAT"));
    }

    @Test
    @DisplayName("E2E (#238): X-Session-Id 헤더 누락 → 401")
    void e2e_history_missingHeader_returns401() {
        // path sessionId UUIDv4 (#948)
        final String pathSessionId = "550e8400-e29b-41d4-a716-11eeeec01a05";
        given()
                .when()
                .get("/api/v1/sessions/{sessionId}/recommendation-history", pathSessionId)
                .then()
                .statusCode(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    @DisplayName("E2E (#238): X-Session-Id 헤더가 path sessionId 와 다르면 → 401 (다른 세션 히스토리 노출 차단)")
    void e2e_history_mismatchedHeader_returns401() {
        // UUIDv4 (#948)
        final String sessionA = "550e8400-e29b-41d4-a716-11eeeec01a06";
        final String sessionB = "550e8400-e29b-41d4-a716-11eeeec01a07";
        postRecommendation(sessionA, 55, 75, "UPBEAT");

        given()
                .header("X-Session-Id", sessionB)
                .when()
                .get("/api/v1/sessions/{sessionId}/recommendation-history", sessionA)
                .then()
                .statusCode(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    @DisplayName("E2E (#238): X-Session-Id 헤더 blank → 401")
    void e2e_history_blankHeader_returns401() {
        // path sessionId UUIDv4 (#948) — 헤더 blank 자체가 401 이라 path 검증까지 가지 않음
        final String pathSessionId = "550e8400-e29b-41d4-a716-11eeeec01a08";
        given()
                .header("X-Session-Id", "")
                .when()
                .get("/api/v1/sessions/{sessionId}/recommendation-history", pathSessionId)
                .then()
                .statusCode(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    @DisplayName("E2E (#423): history 응답 JSON 에 sessionId 키가 존재하지 않는다 (echo 제거 회귀 가드)")
    void e2e_history_responseHasNoSessionIdField() {
        // UUIDv4 (#948)
        final String sessionId = "550e8400-e29b-41d4-a716-11eeeec01a09";
        postRecommendation(sessionId, 55, 75, "UPBEAT");

        final String body = given()
                .header("X-Session-Id", sessionId)
                .when()
                .get("/api/v1/sessions/{sessionId}/recommendation-history", sessionId)
                .then()
                .statusCode(HttpStatus.OK.value())
                .extract()
                .asString();

        // JSON 키 자체가 직렬화되지 않았는지 (Jackson 은 record 미선언 필드를 노출하지 않음)
        assertThat(body).doesNotContain("\"sessionId\"");
    }

    @Test
    @DisplayName("E2E: POST 응답의 recommendations 와 history 의 동일 requestId 항목이 곡 ID·rank 까지 일치 (영속 라운드트립)")
    void e2e_history_persistsRoundtrip() {
        // UUIDv4 (#948)
        final String sessionId = "550e8400-e29b-41d4-a716-11eeeec01a0a";
        // POST → 응답 캡처
        final var post = given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body("""
                        {"sessionId":"%s","voiceRangeLow":55,"voiceRangeHigh":75,"mood":"UPBEAT"}
                        """.formatted(sessionId))
                .when()
                .post("/api/v1/recommendations")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .extract();
        final Integer requestId = post.path("requestId");
        final Integer firstSongId = post.path("recommendations[0].song.id");
        final Integer firstRank = post.path("recommendations[0].rankPosition");

        // history 에서 같은 requestId 의 첫 곡 정보가 일치 (영속 라운드트립 확인)
        given()
                .header("X-Session-Id", sessionId)
                .when()
                .get("/api/v1/sessions/{sessionId}/recommendation-history", sessionId)
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("recommendationHistoryResponses[0].requestId", equalTo(requestId))
                .body("recommendationHistoryResponses[0].recommendations[0].song.id", equalTo(firstSongId))
                .body("recommendationHistoryResponses[0].recommendations[0].rankPosition", equalTo(firstRank));
    }

    private Integer postRecommendation(final String sessionId, final int low, final int high, final String mood) {
        return given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body("""
                        {"sessionId":"%s","voiceRangeLow":%d,"voiceRangeHigh":%d,"mood":"%s"}
                        """.formatted(sessionId, low, high, mood))
                .when()
                .post("/api/v1/recommendations")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .extract().path("requestId");
    }

    private static Song buildSong(
            final String title, final String artist, final MusicalKey key, final Mood mood, final String genre) {
        return Song.builder()
                .title(title).artist(artist).releaseYear(2020)
                .keyOriginal(key).bpm(120).mood(mood)
                .lowMidi(MusicalKeyMidiResolver.rootMidi(key) - 7)
                .highMidi(MusicalKeyMidiResolver.rootMidi(key) + 7)
                .language("ko").genre(genre)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
    }
}
