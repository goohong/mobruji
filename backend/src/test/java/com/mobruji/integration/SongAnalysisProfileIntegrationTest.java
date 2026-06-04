package com.mobruji.integration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
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
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

import io.restassured.RestAssured;

/**
 * E2E: {@code GET /api/v1/songs/{id}} 응답의 {@code analysisProfile} read-model 노출 검증.
 *
 * <p>spec {@code song-analysis-data-and-consumers.md} §5-2 / §7 — 분석 데이터 단일 표면이 소비자
 * 계약대로 곡 상세 응답에 실리는지. {@code energy} null/non-null 두 케이스로 graceful degrade 입력을
 * 함께 검증한다(§5-3).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SongAnalysisProfileIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private SongRepository songRepository;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        songRepository.deleteAll();
    }

    @Test
    @DisplayName("E2E: energy 적재 곡 → analysisProfile 전 필드 round-trip")
    void read_songWithEnergy_exposesFullProfile() {
        final Song song = songRepository.save(Song.builder()
                .title("벚꽃 엔딩").artist("버스커 버스커")
                .keyOriginal(MusicalKey.A_MAJOR).bpm(132).mood(Mood.EMOTIONAL)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .metadataConfidence(0.9)
                .lowMidi(57).highMidi(76)
                .energy(0.42f)
                .build());

        given()
                .when()
                .get("/api/v1/songs/" + song.getId())
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("analysisProfile", notNullValue())
                .body("analysisProfile.lowMidi", equalTo(57))
                .body("analysisProfile.highMidi", equalTo(76))
                .body("analysisProfile.keyOriginal", equalTo("A_MAJOR"))
                .body("analysisProfile.difficulty", equalTo("HARD"))
                .body("analysisProfile.mood", equalTo("EMOTIONAL"))
                .body("analysisProfile.energy", equalTo(0.42f))
                .body("analysisProfile.metadataConfidence", equalTo(0.9f));
    }

    @Test
    @DisplayName("E2E: energy 미적재 곡 → analysisProfile.energy null (graceful degrade 입력)")
    void read_songWithoutEnergy_exposesNullEnergy() {
        final Song song = songRepository.save(Song.builder()
                .title("Dynamite").artist("BTS")
                .keyOriginal(MusicalKey.E_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(59).highMidi(78)
                .build());

        given()
                .when()
                .get("/api/v1/songs/" + song.getId())
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("analysisProfile", notNullValue())
                .body("analysisProfile.lowMidi", equalTo(59))
                .body("analysisProfile.highMidi", equalTo(78))
                .body("analysisProfile.energy", nullValue())
                .body("analysisProfile.mood", nullValue());
    }
}
