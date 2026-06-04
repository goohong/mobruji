package com.mobruji.integration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import com.mobruji.recommendation.domain.Recommendation;
import com.mobruji.recommendation.domain.RecommendationRequestEntity;
import com.mobruji.recommendation.infrastructure.RecommendationRepository;
import com.mobruji.recommendation.infrastructure.RecommendationRequestRepository;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

import io.restassured.RestAssured;

/**
 * GET /api/v1/recommendations/trending E2E (#1488 트렌딩 — 다른 사용자 인기곡).
 *
 * <p>spec: docs/features/trending-recommendation.md §5, §7.
 *
 * <p>추천 결과 row 를 repository 로 직접 시드해 인기곡 순위가 결정성 있게 나오도록 한다 (추천 알고리즘 결과에
 * 의존하지 않고 집계 로직만 검증). 시드 구성:
 *
 * <pre>
 * req1 (UPBEAT, 50-55): A rank1, C rank2
 * req2 (UPBEAT, 50-55): A rank1, C rank2
 * req3 (EMOTIONAL, 80-90): B rank1, A rank2
 * </pre>
 *
 * 인기도(1/rank 합): A=2.5(3회), C=1.0(2회), B=1.0(1회) → 전체 순위 A, C, B (C/B 동점은 등장 횟수로 C 우선).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TrendingIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private SongRepository songRepository;

    @Autowired
    private RecommendationRequestRepository recommendationRequestRepository;

    @Autowired
    private RecommendationRepository recommendationRepository;

    private Long songAId;
    private Long songBId;
    private Long songCId;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        recommendationRepository.deleteAll();
        recommendationRequestRepository.deleteAll();
        songRepository.deleteAll();

        songAId = songRepository.save(buildSong("벚꽃 엔딩", "버스커 버스커", MusicalKey.A_MAJOR, Mood.UPBEAT)).getId();
        songBId = songRepository.save(buildSong("Spring Day", "BTS", MusicalKey.D_MAJOR, Mood.EMOTIONAL)).getId();
        songCId = songRepository.save(buildSong("Eight", "IU", MusicalKey.A_MAJOR, Mood.NOSTALGIC)).getId();

        final Long req1 = saveRequest(50, 55, Mood.UPBEAT);
        final Long req2 = saveRequest(50, 55, Mood.UPBEAT);
        final Long req3 = saveRequest(80, 90, Mood.EMOTIONAL);

        saveRecommendation(req1, songAId, 1);
        saveRecommendation(req1, songCId, 2);
        saveRecommendation(req2, songAId, 1);
        saveRecommendation(req2, songCId, 2);
        saveRecommendation(req3, songBId, 1);
        saveRecommendation(req3, songAId, 2);
    }

    @Test
    @DisplayName("E2E: 필터 없이 트렌딩 → 인기도 순(A, C, B) + rank/등장횟수 노출")
    void e2e_trending_overallRanking() {
        given()
                .when()
                .get("/api/v1/recommendations/trending")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("periodDays", equalTo(7))
                .body("trendingSongs", hasSize(3))
                .body("trendingSongs[0].rankPosition", equalTo(1))
                .body("trendingSongs[0].song.id", equalTo(songAId.intValue()))
                .body("trendingSongs[0].appearanceCount", equalTo(3))
                .body("trendingSongs[0].popularityScore", greaterThanOrEqualTo(2.0f))
                .body("trendingSongs[1].rankPosition", equalTo(2))
                .body("trendingSongs[1].song.id", equalTo(songCId.intValue()))
                .body("trendingSongs[1].appearanceCount", equalTo(2))
                .body("trendingSongs[2].rankPosition", equalTo(3))
                .body("trendingSongs[2].song.id", equalTo(songBId.intValue()));
    }

    @Test
    @DisplayName("E2E: mood=EMOTIONAL → 해당 분위기 요청(req3)만 집계 → B, A (C 제외)")
    void e2e_trending_moodFilter() {
        given()
                .queryParam("mood", "EMOTIONAL")
                .when()
                .get("/api/v1/recommendations/trending")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("mood", equalTo("EMOTIONAL"))
                .body("trendingSongs", hasSize(2))
                .body("trendingSongs[0].song.id", equalTo(songBId.intValue()))
                .body("trendingSongs[1].song.id", equalTo(songAId.intValue()));
    }

    @Test
    @DisplayName("E2E: voiceRange 50-55 → 겹치는 요청(req1,req2)만 집계 → A, C (B 제외 — req3 음역대 비겹침)")
    void e2e_trending_voiceRangeFilter() {
        given()
                .queryParam("voiceRangeLow", 50)
                .queryParam("voiceRangeHigh", 55)
                .when()
                .get("/api/v1/recommendations/trending")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("voiceRangeLow", equalTo(50))
                .body("voiceRangeHigh", equalTo(55))
                .body("trendingSongs", hasSize(2))
                .body("trendingSongs*.song.id", equalTo(List.of(songAId.intValue(), songCId.intValue())));
    }

    @Test
    @DisplayName("E2E: limit=1 → 1위 곡만 반환")
    void e2e_trending_limit() {
        given()
                .queryParam("limit", 1)
                .when()
                .get("/api/v1/recommendations/trending")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("trendingSongs", hasSize(1))
                .body("trendingSongs[0].song.id", equalTo(songAId.intValue()));
    }

    @Test
    @DisplayName("E2E: 데이터 없는 mood(POWERFUL) → 200 + 빈 배열")
    void e2e_trending_emptyWhenNoMatch() {
        given()
                .queryParam("mood", "POWERFUL")
                .when()
                .get("/api/v1/recommendations/trending")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("trendingSongs", hasSize(0));
    }

    @Test
    @DisplayName("E2E: voiceRange 한쪽만 입력 → 400")
    void e2e_trending_partialRange_returns400() {
        given()
                .queryParam("voiceRangeLow", 50)
                .when()
                .get("/api/v1/recommendations/trending")
                .then()
                .statusCode(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    @DisplayName("E2E: periodDays 범위 초과(0) → 400")
    void e2e_trending_invalidPeriod_returns400() {
        given()
                .queryParam("periodDays", 0)
                .when()
                .get("/api/v1/recommendations/trending")
                .then()
                .statusCode(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    @DisplayName("E2E: 정의되지 않은 mood 값 → 400 (Spring enum 변환 단)")
    void e2e_trending_invalidMood_returns400() {
        given()
                .queryParam("mood", "HAPPY")
                .when()
                .get("/api/v1/recommendations/trending")
                .then()
                .statusCode(HttpStatus.BAD_REQUEST.value());
    }

    private Long saveRequest(final int low, final int high, final Mood mood) {
        return recommendationRequestRepository.save(
                RecommendationRequestEntity.create("session-seed", low, high, mood, null, null, null, List.of()))
                .getId();
    }

    private void saveRecommendation(final Long requestId, final Long songId, final int rank) {
        recommendationRepository.save(Recommendation.create(requestId, songId, 1.0, "seed", rank));
    }

    private static Song buildSong(
            final String title, final String artist, final MusicalKey key, final Mood mood) {
        return Song.builder()
                .title(title).artist(artist).releaseYear(2020)
                .keyOriginal(key).bpm(120).mood(mood)
                .language("ko").genre("발라드")
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
    }
}
