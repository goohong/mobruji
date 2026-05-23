package com.mobruji.integration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

import io.restassured.RestAssured;

/**
 * E2E: {@code GET /api/v1/songs/stats} — admin 통계 응답 shape + 인증 게이트 검증.
 *
 * <p>spec rev 14 후속(#208/#212), rev 15 인증 게이트(#224/#228 — v0.3 P0).
 * 본 endpoint 는 {@code X-Admin-Token} 헤더가 필수. 누락/불일치 → 401.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SongStatsIntegrationTest {

    // application-test.yml 의 mobruji.admin.token 과 동일해야 한다.
    private static final String ADMIN_TOKEN = "test-admin-token";

    @LocalServerPort
    private int port;

    @Autowired
    private SongRepository songRepository;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        songRepository.deleteAll();
        // MANUAL_SEED 2 곡, AUDIO_ANALYSIS 1 곡 — confidence 평균 = (1.0 + 1.0 + 0.8) / 3 = 0.9333..
        songRepository.save(Song.builder()
                .title("벚꽃 엔딩").artist("버스커 버스커")
                .keyOriginal(MusicalKey.A_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(57).highMidi(76)
                .build());
        songRepository.save(Song.builder()
                .title("Dynamite").artist("BTS")
                .keyOriginal(MusicalKey.E_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(59).highMidi(78)
                .build());
        songRepository.save(Song.builder()
                .title("Audio Song").artist("X")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.AUDIO_ANALYSIS)
                .metadataConfidence(0.8)
                .lowMidi(60).highMidi(75)
                .build());
    }

    @Test
    @DisplayName("E2E: GET /api/v1/songs/stats — X-Admin-Token 정상 → total/byMetadataSource/avgConfidence/lastBackfillAt/lastAlbumCoverBackfillAt")
    void e2e_stats_returnsExpectedShape() {
        given()
                .header("X-Admin-Token", ADMIN_TOKEN)
                .when()
                .get("/api/v1/songs/stats")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("total", equalTo(3))
                .body("byMetadataSource.MANUAL_SEED", equalTo(2))
                .body("byMetadataSource.AUDIO_ANALYSIS", equalTo(1))
                .body("byMetadataSource.EXTERNAL_API", equalTo(0))
                .body("byMetadataSource.USER_CONTRIBUTION", equalTo(0))
                .body("byMetadataSource.INFERRED", equalTo(0))
                // (1.0 + 1.0 + 0.8) / 3 ≈ 0.9333
                .body("avgConfidence", greaterThanOrEqualTo(0.9f))
                // backfill 미실행 — null
                .body("lastBackfillAt", nullValue())
                // album cover backfill 미실행 — null (이슈 #863)
                .body("lastAlbumCoverBackfillAt", nullValue());
    }

    @Test
    @DisplayName("E2E: 곡 0건이면 total=0, avgConfidence=0.0 (인증 통과 시)")
    void e2e_stats_empty() {
        songRepository.deleteAll();

        given()
                .header("X-Admin-Token", ADMIN_TOKEN)
                .when()
                .get("/api/v1/songs/stats")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("total", equalTo(0))
                .body("avgConfidence", equalTo(0.0f))
                .body("byMetadataSource", notNullValue());
    }

    @Test
    @DisplayName("E2E: X-Admin-Token 헤더 누락 → 401")
    void e2e_stats_missingToken_returns401() {
        given()
                .when()
                .get("/api/v1/songs/stats")
                .then()
                .statusCode(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    @DisplayName("E2E: X-Admin-Token 값 불일치 → 401")
    void e2e_stats_invalidToken_returns401() {
        given()
                .header("X-Admin-Token", "wrong-token")
                .when()
                .get("/api/v1/songs/stats")
                .then()
                .statusCode(HttpStatus.UNAUTHORIZED.value());
    }
}
