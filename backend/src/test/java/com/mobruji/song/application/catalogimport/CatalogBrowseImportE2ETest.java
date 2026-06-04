package com.mobruji.song.application.catalogimport;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.mobruji.song.infrastructure.SongRepository;

import io.restassured.RestAssured;

/**
 * E2E: {@code POST /api/v1/admin/songs/catalog-browse-import} → browse(mock) → DB upsert →
 * {@code GET /api/v1/songs/stats} 의 {@code byMetadataSource.EXTERNAL_API} 증가 반영 (spec
 * {@code song-catalog-expansion.md} §7, #1705). 외부 호출은 mock {@link CatalogBrowseClient} 로 대체한다 —
 * 실 MusicBrainz 미접속.
 *
 * <p>검증 포인트: admin token 누락 → 401, 정상 트리거 시 stats 분포 증가, 동일 시드 재트리거 멱등(중복 row 없음).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CatalogBrowseImportE2ETest {

    private static final String ADMIN_TOKEN = "test-admin-token";

    @LocalServerPort
    private int port;

    @Autowired
    private SongRepository songRepository;

    @MockitoBean
    private CatalogBrowseClient browseClient;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        songRepository.deleteAll();
    }

    @Test
    @DisplayName("E2E: admin token 없으면 401")
    void e2e_missingAdminToken_returns401() {
        given()
                .when()
                .post("/api/v1/admin/songs/catalog-browse-import?dryRun=false&maxArtists=1")
                .then()
                .statusCode(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    @DisplayName("E2E: browse 임포트 후 stats 의 EXTERNAL_API 분포가 적재 곡 수만큼 증가")
    void e2e_browseImport_reflectsInStats() {
        when(browseClient.browseByArtist(anyString(), anyInt(), anyInt())).thenReturn(List.of(
                new BrowsedRecording("mbid-1", "좋니", "윤종신", "KRA401700001", 2017, "k-pop"),
                new BrowsedRecording("mbid-2", "본능적으로", "윤종신", null, 2008, null)));

        given()
                .header("X-Admin-Token", ADMIN_TOKEN)
                .when()
                .post("/api/v1/admin/songs/catalog-browse-import?dryRun=false&maxArtists=1"
                        + "&maxRecordingsPerArtist=2")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("artistsProcessed", equalTo(1))
                .body("inserted", equalTo(2))
                .body("aborted", equalTo(false));

        given()
                .header("X-Admin-Token", ADMIN_TOKEN)
                .when()
                .get("/api/v1/songs/stats")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("total", equalTo(2))
                .body("byMetadataSource.EXTERNAL_API", equalTo(2));
    }

    @Test
    @DisplayName("E2E: 동일 시드 재트리거는 멱등 — 중복 row 없이 EXTERNAL_API 수 불변")
    void e2e_reTrigger_isIdempotent() {
        when(browseClient.browseByArtist(anyString(), anyInt(), anyInt())).thenReturn(List.of(
                new BrowsedRecording("mbid-1", "좋니", "윤종신", "KRA401700001", 2017, "k-pop")));

        triggerImport();
        triggerImport()
                .body("inserted", equalTo(0))
                .body("skipped", greaterThanOrEqualTo(1));

        given()
                .header("X-Admin-Token", ADMIN_TOKEN)
                .when()
                .get("/api/v1/songs/stats")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("total", equalTo(1))
                .body("byMetadataSource.EXTERNAL_API", equalTo(1));
    }

    private io.restassured.response.ValidatableResponse triggerImport() {
        return given()
                .header("X-Admin-Token", ADMIN_TOKEN)
                .when()
                .post("/api/v1/admin/songs/catalog-browse-import?dryRun=false&maxArtists=1"
                        + "&maxRecordingsPerArtist=1")
                .then()
                .statusCode(HttpStatus.OK.value());
    }
}
