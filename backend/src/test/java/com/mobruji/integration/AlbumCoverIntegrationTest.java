package com.mobruji.integration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
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
 * 앨범 커버 URL 의 응답 라운드트립 E2E — ADR 0029 연동 검증.
 *
 * <p>backfill 은 외부 출처 (iTunes / Cover Art Archive) 에 의존하므로 E2E 에서 외부 호출은 하지 않고,
 * backfill 결과가 적용된 곡 ({@link Song#backfillAlbumCoverUrl(String)}) 이 GET 엔드포인트의
 * {@code albumCoverUrl} 필드로 그대로 round-trip 되는지, 미적재 곡은 null 로 graceful degrade 되는지를
 * 박제한다. 출처별 매칭 로직은 단위 테스트 (Itunes/CoverArtArchive/Chained) 가 담당.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AlbumCoverIntegrationTest {

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
    @DisplayName("E2E: backfill 된 albumCoverUrl 이 단건 조회 응답에 round-trip")
    void e2e_albumCoverUrl_roundTrips() {
        // given: backfill 결과가 적용된 곡 (외부 호출 없이 도메인 메서드로 직접 적용)
        final Song song = Song.builder()
                .title("Dynamite").artist("BTS")
                .keyOriginal(MusicalKey.E_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(59).highMidi(78)
                .build();
        song.backfillAlbumCoverUrl("https://coverartarchive.org/release/mbid-bts/500.jpg");
        final Long id = songRepository.save(song).getId();

        // when/then
        given()
                .when()
                .get("/api/v1/songs/" + id)
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("albumCoverUrl",
                        equalTo("https://coverartarchive.org/release/mbid-bts/500.jpg"));
    }

    @Test
    @DisplayName("E2E: 매칭 실패로 미적재된 곡은 albumCoverUrl 이 null (graceful)")
    void e2e_unmatchedSong_albumCoverUrlNull() {
        // given: backfill 미적용 곡
        final Long id = songRepository.save(Song.builder()
                .title("무명 인디곡").artist("무명 아티스트")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(60).highMidi(72)
                .build()).getId();

        // when/then
        given()
                .when()
                .get("/api/v1/songs/" + id)
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("albumCoverUrl", nullValue());
    }
}
