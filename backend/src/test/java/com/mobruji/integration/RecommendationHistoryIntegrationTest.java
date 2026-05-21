package com.mobruji.integration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
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

import com.mobruji.recommendation.infrastructure.RecommendationRepository;
import com.mobruji.recommendation.infrastructure.RecommendationRequestRepository;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.Mood;
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
        final String sessionId = "rec-history-asc";
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
                .body("recommendationHistoryResponses[0].sessionId", equalTo(sessionId))
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
        given()
                .header("X-Session-Id", "no-such-session")
                .when()
                .get("/api/v1/sessions/{sessionId}/recommendation-history", "no-such-session")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("recommendationHistoryResponses", hasSize(0));
    }

    @Test
    @DisplayName("E2E: 다른 sessionId 의 추천은 응답에 포함되지 않는다")
    void e2e_history_isolatedBySessionId() {
        postRecommendation("session-A", 55, 75, "UPBEAT");
        postRecommendation("session-B", 50, 80, "EMOTIONAL");

        given()
                .header("X-Session-Id", "session-A")
                .when()
                .get("/api/v1/sessions/{sessionId}/recommendation-history", "session-A")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("recommendationHistoryResponses", hasSize(1))
                .body("recommendationHistoryResponses[0].sessionId", equalTo("session-A"))
                .body("recommendationHistoryResponses[0].mood", equalTo("UPBEAT"));
    }

    @Test
    @DisplayName("E2E (#238): X-Session-Id 헤더 누락 → 401")
    void e2e_history_missingHeader_returns401() {
        given()
                .when()
                .get("/api/v1/sessions/{sessionId}/recommendation-history", "session-A")
                .then()
                .statusCode(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    @DisplayName("E2E (#238): X-Session-Id 헤더가 path sessionId 와 다르면 → 401 (다른 세션 히스토리 노출 차단)")
    void e2e_history_mismatchedHeader_returns401() {
        postRecommendation("session-A", 55, 75, "UPBEAT");

        given()
                .header("X-Session-Id", "session-B")
                .when()
                .get("/api/v1/sessions/{sessionId}/recommendation-history", "session-A")
                .then()
                .statusCode(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    @DisplayName("E2E (#238): X-Session-Id 헤더 blank → 401")
    void e2e_history_blankHeader_returns401() {
        given()
                .header("X-Session-Id", "")
                .when()
                .get("/api/v1/sessions/{sessionId}/recommendation-history", "session-A")
                .then()
                .statusCode(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    @DisplayName("E2E: POST 응답의 recommendations 와 history 의 동일 requestId 항목이 곡 ID·rank 까지 일치 (영속 라운드트립)")
    void e2e_history_persistsRoundtrip() {
        final String sessionId = "rec-history-roundtrip";
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
                .language("ko").genre(genre)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
    }
}
