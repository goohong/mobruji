package com.mobruji.song.application.catalogimport;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.mobruji.song.application.catalogimport.MetadataOnlyImportCommand.CandidateEntry;
import com.mobruji.song.application.catalogimport.MetadataOnlyImportCommand.ImportSummary;
import com.mobruji.song.infrastructure.SongRepository;

import io.restassured.RestAssured;

/**
 * E2E: 메타-only 임포트 배치 → DB → {@code GET /api/v1/songs/stats} 의 {@code byMetadataSource.EXTERNAL_API}
 * 증가 반영 (spec {@code song-catalog-expansion.md} §7). 외부 호출은 stub {@link SongMetadataLookupClient} 로 대체.
 *
 * <p>임포트 배치({@link MetadataOnlyImportCommand}) 는 {@code @Profile("!test")} 라 test 컨텍스트에 없으므로
 * autowired 의존성으로 직접 조립해 실행한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CatalogImportStatsIntegrationTest {

    private static final String ADMIN_TOKEN = "test-admin-token";

    @LocalServerPort
    private int port;

    @Autowired
    private SongRepository songRepository;

    @Autowired
    private CatalogImportProperties properties;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        songRepository.deleteAll();
    }

    @Test
    @DisplayName("E2E: 메타-only 임포트 후 stats 의 EXTERNAL_API 분포가 임포트 곡 수만큼 증가")
    void e2e_import_reflectsInStats() {
        final SongMetadataLookupClient stubLookup = (title, artist) -> Optional.of(new ImportedSongMetadata("mbid-"
                + title, null, 2020, "k-pop"));
        final MetadataOnlyImportCommand command = new MetadataOnlyImportCommand(songRepository, stubLookup, properties,
                objectMapper);

        final ImportSummary summary = command.runImport(List.of(
                new CandidateEntry("좋니", "윤종신"),
                new CandidateEntry("봄날", "방탄소년단")));

        org.assertj.core.api.Assertions.assertThat(summary.inserted()).isEqualTo(2);

        given()
                .header("X-Admin-Token", ADMIN_TOKEN)
                .when()
                .get("/api/v1/songs/stats")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("total", equalTo(2))
                .body("byMetadataSource.EXTERNAL_API", equalTo(2))
                .body("byMetadataSource.MANUAL_SEED", equalTo(0));
    }

    @Test
    @DisplayName("E2E: 동일 후보 재임포트는 멱등 — 중복 row 없이 EXTERNAL_API 수 불변")
    void e2e_reimport_isIdempotent() {
        final SongMetadataLookupClient stubLookup = (title, artist) -> Optional.of(new ImportedSongMetadata("mbid-"
                + title, null, 2020, "k-pop"));
        final MetadataOnlyImportCommand command = new MetadataOnlyImportCommand(songRepository, stubLookup, properties,
                objectMapper);
        final List<CandidateEntry> candidates = List.of(new CandidateEntry("좋니", "윤종신"));

        command.runImport(candidates);
        final ImportSummary second = command.runImport(candidates);

        org.assertj.core.api.Assertions.assertThat(second.skipped()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(second.inserted()).isZero();

        given()
                .header("X-Admin-Token", ADMIN_TOKEN)
                .when()
                .get("/api/v1/songs/stats")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("total", equalTo(1))
                .body("byMetadataSource.EXTERNAL_API", equalTo(1));
    }
}
