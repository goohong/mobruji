package com.mobruji.integration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
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

import com.mobruji.recommendation.RecommendationRepository;
import com.mobruji.recommendation.RecommendationRequestRepository;
import com.mobruji.song.MetadataSource;
import com.mobruji.song.Mood;
import com.mobruji.song.MusicalKey;
import com.mobruji.song.Song;
import com.mobruji.song.SongRepository;

import io.restassured.RestAssured;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RecommendationIntegrationTest {

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
        // 곡 5개 시드 (다양성 후처리 검증 가능)
        songRepository.save(buildSong("벚꽃 엔딩", "버스커 버스커", MusicalKey.A_MAJOR, Mood.EMOTIONAL, "발라드"));
        songRepository.save(buildSong("Dynamite", "BTS", MusicalKey.E_MAJOR, Mood.UPBEAT, "댄스"));
        songRepository.save(buildSong("Spring Day", "BTS", MusicalKey.D_SHARP_MAJOR, Mood.EMOTIONAL, "발라드"));
        songRepository.save(buildSong("Eight", "IU", MusicalKey.A_MAJOR, Mood.NOSTALGIC, "팝"));
        songRepository.save(buildSong("Lilac", "IU", MusicalKey.D_MAJOR, Mood.UPBEAT, "팝"));
    }

    @Test
    @DisplayName("E2E: 추천 요청 → 결과 N개 + GET 재조회 일치")
    void e2e_createThenRead() {
        final String createBody = """
                {
                  "sessionId": "rec-e2e-1",
                  "voiceRangeLow": 55,
                  "voiceRangeHigh": 75,
                  "mood": "UPBEAT"
                }
                """;

        // POST → 201
        final Integer requestId = given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(createBody)
                .when()
                .post("/api/v1/recommendations")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .body("requestId", notNullValue())
                .body("recommendations.size()", greaterThan(0))
                .body("recommendations.size()", lessThanOrEqualTo(10))
                .body("recommendations[0].rankPosition", equalTo(1))
                .body("recommendations[0].matchReason", notNullValue())
                .extract().path("requestId");

        // GET 재조회 → 동일
        given()
                .when()
                .get("/api/v1/recommendations/" + requestId)
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("requestId", equalTo(requestId))
                .body("recommendations.size()", greaterThan(0));
    }

    @Test
    @DisplayName("E2E: 없는 추천 ID는 404")
    void e2e_notFound() {
        given()
                .when()
                .get("/api/v1/recommendations/999999")
                .then()
                .statusCode(HttpStatus.NOT_FOUND.value());
    }

    @Test
    @DisplayName("E2E: 같은 아티스트 ≤ 2 다양성 후처리 작동")
    void e2e_diversityRespectsArtistCap() {
        // BTS 2곡, IU 2곡 시드. 모두 점수 높게 잡혀도 결과 내 BTS 곡은 최대 2 (cap 2)
        final String createBody = """
                {
                  "sessionId": "rec-e2e-2",
                  "voiceRangeLow": 50,
                  "voiceRangeHigh": 80,
                  "mood": "EMOTIONAL"
                }
                """;

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(createBody)
                .when()
                .post("/api/v1/recommendations")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                // BTS 곡이 결과에 포함되더라도 최대 2개 (cap)
                .body("recommendations.findAll { it.song.artist == 'BTS' }.size()", lessThanOrEqualTo(2));
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
