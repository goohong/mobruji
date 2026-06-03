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

import com.mobruji.song.domain.Difficulty;
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
                .lowMidi(57).highMidi(76)
                .build());
        songRepository.save(Song.builder()
                .title("Dynamite").artist("BTS").releaseYear(2020)
                .keyOriginal(MusicalKey.E_MAJOR).bpm(114).mood(Mood.UPBEAT)
                .language("en").genre("댄스").tjNumber("29062")
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(59).highMidi(78)
                .build());
    }

    @Test
    @DisplayName("E2E: 키워드 검색으로 제목 일치 곡을 찾는다 (wrapper items)")
    void e2e_searchByTitle() {
        given()
                .when()
                .get("/api/v1/songs?keyword=벚꽃")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("items.size()", greaterThanOrEqualTo(1))
                .body("items[0].title", equalTo("벚꽃 엔딩"));
    }

    @Test
    @DisplayName("E2E: 키워드 검색으로 아티스트 일치 곡을 찾는다 (wrapper items)")
    void e2e_searchByArtist() {
        given()
                .when()
                .get("/api/v1/songs?keyword=BTS")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("items.size()", greaterThanOrEqualTo(1))
                .body("items[0].artist", equalTo("BTS"));
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

    @Test
    @DisplayName("E2E: 응답에 difficulty + lowestNoteName/highestNoteName 포함")
    void e2e_responseIncludesDifficultyAndNoteNames() {
        // given: 벚꽃 엔딩(highMidi=76)은 HARD, 노트명은 A3 ~ E5
        final Long id = songRepository.findAll().stream()
                .filter(song -> "벚꽃 엔딩".equals(song.getTitle()))
                .findFirst().orElseThrow().getId();

        // when/then
        given()
                .when()
                .get("/api/v1/songs/" + id)
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("difficulty", equalTo(Difficulty.HARD.name()))
                .body("lowMidi", equalTo(57))
                .body("highMidi", equalTo(76))
                .body("lowestNoteName", equalTo("A3"))
                .body("highestNoteName", equalTo("E5"));
    }

    @Test
    @DisplayName("E2E: energy 적재 곡은 응답에 round-trip, 미적재 곡은 null")
    void e2e_responseIncludesEnergy() {
        // given: energy 채운 곡 + 미적재 곡(graceful degrade 대상)
        final Long withEnergyId = songRepository.save(Song.builder()
                .title("Energy Song").artist("Energy Artist")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(60).highMidi(72)
                .energy(0.88f)
                .build()).getId();
        final Long nullEnergyId = songRepository.findAll().stream()
                .filter(song -> "벚꽃 엔딩".equals(song.getTitle()))
                .findFirst().orElseThrow().getId();

        // when/then: 적재 곡은 round-trip
        given()
                .when()
                .get("/api/v1/songs/" + withEnergyId)
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("energy", equalTo(0.88f));

        // 미적재 곡은 null (소비자 graceful degrade)
        given()
                .when()
                .get("/api/v1/songs/" + nullEnergyId)
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("energy", org.hamcrest.Matchers.nullValue());
    }

    @Test
    @DisplayName("Repository 라운드트립: lowMidi/highMidi/difficulty 영속")
    void persist_roundTrip_preservesDifficultyAndMidi() {
        // given
        final Song song = songRepository.save(Song.builder()
                .title("Test Song").artist("Test Artist")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(60).highMidi(78)
                .build());

        // when
        final Song reloaded = songRepository.findById(song.getId()).orElseThrow();

        // then
        org.assertj.core.api.Assertions.assertThat(reloaded.getLowMidi()).isEqualTo(60);
        org.assertj.core.api.Assertions.assertThat(reloaded.getHighMidi()).isEqualTo(78);
        org.assertj.core.api.Assertions.assertThat(reloaded.getDifficulty()).isEqualTo(Difficulty.HARD);
    }
}
