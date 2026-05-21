package com.mobruji.integration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
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

import com.mobruji.feedback.infrastructure.LikeRepository;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

import io.restassured.RestAssured;

/**
 * E2E (07-testing-guide §E2E 필수 룰) — Like toggle 성공 케이스.
 *
 * <p>given: 시드 Song 1건 + 빈 like 테이블. when: POST /api/v1/likes → 다시 POST(취소) → GET 목록.
 * then: 첫 POST는 liked=true, 두 번째 POST는 liked=false, GET은 비어 있다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LikeFeedbackIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private SongRepository songRepository;

    @Autowired
    private LikeRepository likeRepository;

    private Long seededSongId;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        likeRepository.deleteAll();
        songRepository.deleteAll();
        seededSongId = songRepository.save(Song.builder()
                .title("좋아요 시드 곡").artist("테스트 아티스트")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(48).highMidi(67)
                .build())
                .getId();
    }

    @Test
    @DisplayName("E2E: POST(toggle on) → GET 1건 → POST(toggle off) → GET 빈 목록")
    void e2e_toggleLike_succeeds() {
        final String sessionId = "e2e-like-session";
        final String requestBody = """
                {"sessionId":"%s","songId":%d}
                """.formatted(sessionId, seededSongId);

        // 1) POST → liked=true
        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(requestBody)
                .when()
                .post("/api/v1/likes")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("liked", equalTo(true))
                .body("songId", equalTo(seededSongId.intValue()));

        // 2) GET → 1건
        given()
                .when()
                .get("/api/v1/sessions/" + sessionId + "/likes")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("$", hasSize(1))
                .body("[0].sessionId", equalTo(sessionId))
                .body("[0].songId", equalTo(seededSongId.intValue()))
                .body("[0].id", notNullValue())
                .body("[0].createdAt", notNullValue());

        // 3) POST 다시 → liked=false (toggle off)
        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(requestBody)
                .when()
                .post("/api/v1/likes")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("liked", equalTo(false));

        // 4) GET → 빈 목록
        given()
                .when()
                .get("/api/v1/sessions/" + sessionId + "/likes")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("$", hasSize(0));
    }

    @Test
    @DisplayName("E2E: 존재하지 않는 songId로 POST → 404")
    void e2e_unknownSongId_returns404() {
        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body("""
                        {"sessionId":"e2e-like-404","songId":999999}
                        """)
                .when()
                .post("/api/v1/likes")
                .then()
                .statusCode(HttpStatus.NOT_FOUND.value());
    }
}
