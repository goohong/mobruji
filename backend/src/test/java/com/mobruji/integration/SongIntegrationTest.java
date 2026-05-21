package com.mobruji.integration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SongIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private SongRepository songRepository;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        songRepository.deleteAll();
        songRepository.save(Song.builder()
                .title("벚꽃 엔딩").artist("버스커 버스커").releaseYear(2012)
                .keyOriginal(MusicalKey.A_MAJOR).bpm(132).mood(Mood.EMOTIONAL)
                .language("ko").genre("발라드").tjNumber("60540")
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build());
        songRepository.save(Song.builder()
                .title("Dynamite").artist("BTS").releaseYear(2020)
                .keyOriginal(MusicalKey.E_MAJOR).bpm(114).mood(Mood.UPBEAT)
                .language("en").genre("댄스").tjNumber("29062")
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build());
    }

    @Test
    @DisplayName("E2E: 키워드 검색으로 제목 일치 곡을 찾는다")
    void e2e_searchByTitle() {
        given()
                .when()
                .get("/api/v1/songs?keyword=벚꽃")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("size()", greaterThanOrEqualTo(1))
                .body("[0].title", equalTo("벚꽃 엔딩"));
    }

    @Test
    @DisplayName("E2E: 키워드 검색으로 아티스트 일치 곡을 찾는다")
    void e2e_searchByArtist() {
        given()
                .when()
                .get("/api/v1/songs?keyword=BTS")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("size()", greaterThanOrEqualTo(1))
                .body("[0].artist", equalTo("BTS"));
    }

    @Test
    @DisplayName("E2E: ID로 단건 조회")
    void e2e_readById() {
        final Long id = songRepository.findAll().get(0).getId();

        given()
                .when()
                .get("/api/v1/songs/" + id)
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("id", equalTo(id.intValue()));
    }

    @Test
    @DisplayName("E2E: 없는 ID는 404")
    void e2e_notFound_returns404() {
        given()
                .when()
                .get("/api/v1/songs/999999")
                .then()
                .statusCode(HttpStatus.NOT_FOUND.value());
    }
}
