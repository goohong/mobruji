package com.mobruji.integration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import com.mobruji.feedback.infrastructure.BookmarkRepository;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

import io.restassured.RestAssured;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BookmarkFeedbackIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private SongRepository songRepository;

    @Autowired
    private BookmarkRepository bookmarkRepository;

    private Long seededSongId;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        bookmarkRepository.deleteAll();
        songRepository.deleteAll();
        seededSongId = songRepository.save(Song.builder()
                .title("북마크 시드 곡").artist("테스트 아티스트")
                .keyOriginal(MusicalKey.D_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(50).highMidi(69)
                .build())
                .getId();
    }

    @Test
    @DisplayName("E2E: POST(toggle on) → GET 1건 → POST(toggle off) → GET 빈 목록")
    void e2e_toggleBookmark_succeeds() {
        final String sessionId = "e2e-bm-session";
        final String requestBody = """
                {"sessionId":"%s","songId":%d}
                """.formatted(sessionId, seededSongId);

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(requestBody)
                .when()
                .post("/api/v1/bookmarks")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("bookmarked", equalTo(true))
                .body("songId", equalTo(seededSongId.intValue()));

        given()
                .when()
                .get("/api/v1/sessions/" + sessionId + "/bookmarks")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("$", hasSize(1))
                .body("[0].sessionId", equalTo(sessionId))
                .body("[0].songId", equalTo(seededSongId.intValue()));

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(requestBody)
                .when()
                .post("/api/v1/bookmarks")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("bookmarked", equalTo(false));

        given()
                .when()
                .get("/api/v1/sessions/" + sessionId + "/bookmarks")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("$", hasSize(0));
    }
}
