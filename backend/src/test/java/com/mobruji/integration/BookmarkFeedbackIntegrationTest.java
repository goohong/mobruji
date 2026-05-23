package com.mobruji.integration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import com.mobruji.feedback.domain.Bookmark;
import com.mobruji.feedback.infrastructure.BookmarkRepository;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

import io.restassured.RestAssured;

/**
 * E2E (07-testing-guide §E2E 필수 룰) — Bookmark toggle 성공 케이스 + 북마크 목록 GET (Song join + 페이지네이션 +
 * SessionAuthGuard). {@link LikeFeedbackIntegrationTest} 와 동일 패턴.
 */
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
    @DisplayName("E2E: POST(toggle on) → GET 1건(Song join 포함) → POST(toggle off) → GET 빈 목록")
    void e2e_toggleBookmark_succeeds() {
        final String sessionId = "e2e-bm-session";
        final String requestBody = """
                {"sessionId":"%s","songId":%d}
                """.formatted(sessionId, seededSongId);

        given()
                .header("X-Session-Id", sessionId)
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(requestBody)
                .when()
                .post("/api/v1/bookmarks")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("bookmarked", equalTo(true))
                .body("songId", equalTo(seededSongId.intValue()));

        given()
                .header("X-Session-Id", sessionId)
                .when()
                .get("/api/v1/sessions/" + sessionId + "/bookmarks")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("responses", hasSize(1))
                .body("responses[0].id", notNullValue())
                .body("responses[0].bookmarkedAt", notNullValue())
                .body("responses[0].song.id", equalTo(seededSongId.intValue()))
                .body("responses[0].song.title", equalTo("북마크 시드 곡"))
                .body("responses[0].song.artist", equalTo("테스트 아티스트"))
                .body("totalCount", equalTo(1))
                .body("page", equalTo(0))
                .body("size", equalTo(20))
                .body("hasNext", equalTo(false));

        given()
                .header("X-Session-Id", sessionId)
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(requestBody)
                .when()
                .post("/api/v1/bookmarks")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("bookmarked", equalTo(false));

        given()
                .header("X-Session-Id", sessionId)
                .when()
                .get("/api/v1/sessions/" + sessionId + "/bookmarks")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("responses", hasSize(0))
                .body("totalCount", equalTo(0));
    }

    @Nested
    @DisplayName("session-bound 인증 (spec §5-2-1, ADR-0011)")
    class SessionBoundAuth {

        @Test
        @DisplayName("X-Session-Id 헤더 누락 → 401")
        void missingHeader_returns401() {
            final String sessionId = "e2e-bm-auth";
            bookmarkRepository.save(Bookmark.create(sessionId, seededSongId));

            given()
                    .when()
                    .get("/api/v1/sessions/" + sessionId + "/bookmarks")
                    .then()
                    .statusCode(HttpStatus.UNAUTHORIZED.value())
                    .body("message", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(sessionId)));
        }

        @Test
        @DisplayName("X-Session-Id 불일치 → 401")
        void mismatchedHeader_returns401() {
            final String sessionA = "e2e-bm-A";
            final String sessionB = "e2e-bm-B";
            bookmarkRepository.save(Bookmark.create(sessionA, seededSongId));

            given()
                    .header("X-Session-Id", sessionB)
                    .when()
                    .get("/api/v1/sessions/" + sessionA + "/bookmarks")
                    .then()
                    .statusCode(HttpStatus.UNAUTHORIZED.value());
        }

        @Test
        @DisplayName("X-Session-Id 일치하지만 북마크 0건 → 200 + 빈 배열")
        void matchedHeader_emptyResult_returns200() {
            final String sessionId = "e2e-bm-empty";

            given()
                    .header("X-Session-Id", sessionId)
                    .when()
                    .get("/api/v1/sessions/" + sessionId + "/bookmarks")
                    .then()
                    .statusCode(HttpStatus.OK.value())
                    .body("responses", hasSize(0))
                    .body("totalCount", equalTo(0));
        }

        @Test
        @DisplayName("POST /api/v1/bookmarks: 헤더 누락 → 401 (toggle 실행 차단)")
        void postBookmarks_missingHeader_returns401() {
            final String sessionId = "e2e-bm-post-missing";
            final String requestBody = """
                    {"sessionId":"%s","songId":%d}
                    """.formatted(sessionId, seededSongId);

            given()
                    .contentType(MediaType.APPLICATION_JSON_VALUE)
                    .body(requestBody)
                    .when()
                    .post("/api/v1/bookmarks")
                    .then()
                    .statusCode(HttpStatus.UNAUTHORIZED.value());
            org.junit.jupiter.api.Assertions.assertEquals(
                    0, bookmarkRepository.countBySessionId(sessionId));
        }

        @Test
        @DisplayName("POST /api/v1/bookmarks: body sessionId ≠ X-Session-Id 헤더 → 401")
        void postBookmarks_mismatchedHeader_returns401() {
            final String bodySessionId = "e2e-bm-post-A";
            final String headerSessionId = "e2e-bm-post-B";
            final String requestBody = """
                    {"sessionId":"%s","songId":%d}
                    """.formatted(bodySessionId, seededSongId);

            given()
                    .header("X-Session-Id", headerSessionId)
                    .contentType(MediaType.APPLICATION_JSON_VALUE)
                    .body(requestBody)
                    .when()
                    .post("/api/v1/bookmarks")
                    .then()
                    .statusCode(HttpStatus.UNAUTHORIZED.value());
            org.junit.jupiter.api.Assertions.assertEquals(
                    0, bookmarkRepository.countBySessionId(bodySessionId));
            org.junit.jupiter.api.Assertions.assertEquals(
                    0, bookmarkRepository.countBySessionId(headerSessionId));
        }
    }
}
