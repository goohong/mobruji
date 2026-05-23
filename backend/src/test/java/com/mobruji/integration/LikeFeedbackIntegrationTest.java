package com.mobruji.integration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

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

import com.mobruji.feedback.domain.Like;
import com.mobruji.feedback.infrastructure.LikeRepository;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

import io.restassured.RestAssured;

/**
 * E2E (07-testing-guide §E2E 필수 룰) — Like toggle 성공 케이스 + 좋아요 목록 GET (Song join + 페이지네이션 +
 * SessionAuthGuard).
 *
 * <p>spec recommendation-history-and-feedback.md §5-2 / §5-2-1 / §7 (인증 E2E 4종).
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
    @DisplayName("E2E: POST(toggle on) → GET 1건(Song join 포함) → POST(toggle off) → GET 빈 목록")
    void e2e_toggleLike_succeeds() {
        // sessionId 는 UUIDv4 (ADR-0011 / #948 SessionIdPatterns 강제) — 마지막 12자 끝부분에 케이스 식별자 hex 부착
        final String sessionId = "550e8400-e29b-41d4-a716-11ee5e55101a";
        final String requestBody = """
                {"sessionId":"%s","songId":%d}
                """.formatted(sessionId, seededSongId);

        // 1) POST → liked=true
        given()
                .header("X-Session-Id", sessionId)
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(requestBody)
                .when()
                .post("/api/v1/likes")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("liked", equalTo(true))
                .body("songId", equalTo(seededSongId.intValue()));

        // 2) GET → 1건 + Song join + 페이지네이션 메타
        given()
                .header("X-Session-Id", sessionId)
                .when()
                .get("/api/v1/sessions/" + sessionId + "/likes")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("responses", hasSize(1))
                .body("responses[0].id", notNullValue())
                .body("responses[0].likedAt", notNullValue())
                .body("responses[0].song.id", equalTo(seededSongId.intValue()))
                .body("responses[0].song.title", equalTo("좋아요 시드 곡"))
                .body("responses[0].song.artist", equalTo("테스트 아티스트"))
                .body("responses[0].song.lowMidi", equalTo(48))
                .body("responses[0].song.highMidi", equalTo(67))
                .body("page", equalTo(0))
                .body("size", equalTo(20))
                .body("totalCount", equalTo(1))
                .body("hasNext", equalTo(false));

        // 3) POST 다시 → liked=false (toggle off)
        given()
                .header("X-Session-Id", sessionId)
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(requestBody)
                .when()
                .post("/api/v1/likes")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("liked", equalTo(false));

        // 4) GET → 빈 목록 (404 아님)
        given()
                .header("X-Session-Id", sessionId)
                .when()
                .get("/api/v1/sessions/" + sessionId + "/likes")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("responses", hasSize(0))
                .body("totalCount", equalTo(0))
                .body("hasNext", equalTo(false));
    }

    @Test
    @DisplayName("E2E: 존재하지 않는 songId로 POST → 404")
    void e2e_unknownSongId_returns404() {
        // UUIDv4 (#948)
        final String sessionId404 = "550e8400-e29b-41d4-a716-11ee5e554040";
        given()
                .header("X-Session-Id", sessionId404)
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body("""
                        {"sessionId":"%s","songId":999999}
                        """.formatted(sessionId404))
                .when()
                .post("/api/v1/likes")
                .then()
                .statusCode(HttpStatus.NOT_FOUND.value());
    }

    /**
     * spec §5-2-1 / ADR-0011 — session-bound GET 엔드포인트 인증 케이스.
     */
    @Nested
    @DisplayName("session-bound 인증 (spec §5-2-1, ADR-0011)")
    class SessionBoundAuth {

        @Test
        @DisplayName("X-Session-Id 헤더 누락 → 401, sessionId 원문 미노출")
        void missingHeader_returns401_withoutLeakingSessionId() {
            // UUIDv4 (#948)
            final String sessionId = "550e8400-e29b-41d4-a716-11ee5e554a01";
            likeRepository.save(Like.create(sessionId, seededSongId));

            given()
                    .when()
                    .get("/api/v1/sessions/" + sessionId + "/likes")
                    .then()
                    .statusCode(HttpStatus.UNAUTHORIZED.value())
                    // 401 응답에 sessionId 원문이 노출되지 않아야 함 (security-policy.md §3).
                    .body("message", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(sessionId)));
        }

        @Test
        @DisplayName("X-Session-Id 헤더와 path sessionId 불일치 → 401")
        void mismatchedHeader_returns401() {
            // UUIDv4 (#948)
            final String sessionA = "550e8400-e29b-41d4-a716-11ee5e554a02";
            final String sessionB = "550e8400-e29b-41d4-a716-11ee5e554a03";
            likeRepository.save(Like.create(sessionA, seededSongId));

            given()
                    .header("X-Session-Id", sessionB)
                    .when()
                    .get("/api/v1/sessions/" + sessionA + "/likes")
                    .then()
                    .statusCode(HttpStatus.UNAUTHORIZED.value());
        }

        @Test
        @DisplayName("X-Session-Id 일치하지만 좋아요 0건 → 200 + 빈 배열 (404 아님)")
        void matchedHeader_emptyResult_returns200() {
            // UUIDv4 (#948)
            final String sessionId = "550e8400-e29b-41d4-a716-11ee5e554a04";

            given()
                    .header("X-Session-Id", sessionId)
                    .when()
                    .get("/api/v1/sessions/" + sessionId + "/likes")
                    .then()
                    .statusCode(HttpStatus.OK.value())
                    .body("responses", hasSize(0))
                    .body("totalCount", equalTo(0))
                    .body("page", equalTo(0))
                    .body("hasNext", equalTo(false));
        }

        @Test
        @DisplayName("X-Session-Id blank → 401")
        void blankHeader_returns401() {
            // UUIDv4 (#948) — 헤더는 blank, path 는 valid UUIDv4
            final String sessionId = "550e8400-e29b-41d4-a716-11ee5e554a05";

            given()
                    .header("X-Session-Id", "   ")
                    .when()
                    .get("/api/v1/sessions/" + sessionId + "/likes")
                    .then()
                    .statusCode(HttpStatus.UNAUTHORIZED.value());
        }

        @Test
        @DisplayName("POST /api/v1/likes: 헤더 누락 → 401 (toggle 실행 차단)")
        void postLikes_missingHeader_returns401() {
            // UUIDv4 (#948)
            final String sessionId = "550e8400-e29b-41d4-a716-11ee5e554a06";
            final String requestBody = """
                    {"sessionId":"%s","songId":%d}
                    """.formatted(sessionId, seededSongId);

            given()
                    .contentType(MediaType.APPLICATION_JSON_VALUE)
                    .body(requestBody)
                    .when()
                    .post("/api/v1/likes")
                    .then()
                    .statusCode(HttpStatus.UNAUTHORIZED.value());
            // 가드가 service 전에 거부 → 좋아요가 생성되지 않아야 함
            org.junit.jupiter.api.Assertions.assertEquals(
                    0, likeRepository.countBySessionId(sessionId));
        }

        @Test
        @DisplayName("POST /api/v1/likes: body sessionId ≠ X-Session-Id 헤더 → 401")
        void postLikes_mismatchedHeader_returns401() {
            // UUIDv4 (#948)
            final String bodySessionId = "550e8400-e29b-41d4-a716-11ee5e554a07";
            final String headerSessionId = "550e8400-e29b-41d4-a716-11ee5e554a08";
            final String requestBody = """
                    {"sessionId":"%s","songId":%d}
                    """.formatted(bodySessionId, seededSongId);

            given()
                    .header("X-Session-Id", headerSessionId)
                    .contentType(MediaType.APPLICATION_JSON_VALUE)
                    .body(requestBody)
                    .when()
                    .post("/api/v1/likes")
                    .then()
                    .statusCode(HttpStatus.UNAUTHORIZED.value());
            org.junit.jupiter.api.Assertions.assertEquals(
                    0, likeRepository.countBySessionId(bodySessionId));
            org.junit.jupiter.api.Assertions.assertEquals(
                    0, likeRepository.countBySessionId(headerSessionId));
        }
    }

    /**
     * spec §5-2 — `Page<LikeWithSongResponse>` 응답 / 페이지네이션 query parameter.
     */
    @Nested
    @DisplayName("페이지네이션")
    class Pagination {

        @Test
        @DisplayName("page=0&size=2 → 첫 페이지 2건, hasNext=true; page=1&size=2 → 1건, hasNext=false")
        void pageSlicing_succeeds() {
            // UUIDv4 (#948)
            final String sessionId = "550e8400-e29b-41d4-a716-11ee5e554a09";

            // 3건의 좋아요를 시간 차로 등록 (createdAt DESC 정렬 검증)
            final Long songA = songRepository.save(Song.builder()
                    .title("곡A").artist("가수").keyOriginal(MusicalKey.C_MAJOR)
                    .metadataSource(MetadataSource.MANUAL_SEED).build()).getId();
            final Long songB = songRepository.save(Song.builder()
                    .title("곡B").artist("가수").keyOriginal(MusicalKey.C_MAJOR)
                    .metadataSource(MetadataSource.MANUAL_SEED).build()).getId();
            final Long songC = songRepository.save(Song.builder()
                    .title("곡C").artist("가수").keyOriginal(MusicalKey.C_MAJOR)
                    .metadataSource(MetadataSource.MANUAL_SEED).build()).getId();
            // save 순서 = createdAt 순서. 최신순(DESC)이면 C, B, A
            likeRepository.save(Like.create(sessionId, songA));
            sleepMillis(10L);
            likeRepository.save(Like.create(sessionId, songB));
            sleepMillis(10L);
            likeRepository.save(Like.create(sessionId, songC));

            given()
                    .header("X-Session-Id", sessionId)
                    .queryParam("page", 0).queryParam("size", 2)
                    .when()
                    .get("/api/v1/sessions/" + sessionId + "/likes")
                    .then()
                    .statusCode(HttpStatus.OK.value())
                    .body("responses", hasSize(2))
                    .body("responses.song.title", contains("곡C", "곡B"))
                    .body("totalCount", equalTo(3))
                    .body("page", equalTo(0))
                    .body("size", equalTo(2))
                    .body("hasNext", equalTo(true));

            given()
                    .header("X-Session-Id", sessionId)
                    .queryParam("page", 1).queryParam("size", 2)
                    .when()
                    .get("/api/v1/sessions/" + sessionId + "/likes")
                    .then()
                    .statusCode(HttpStatus.OK.value())
                    .body("responses", hasSize(1))
                    .body("responses[0].song.title", equalTo("곡A"))
                    .body("totalCount", equalTo(3))
                    .body("hasNext", equalTo(false));
        }

        @Test
        @DisplayName("size > 100 → 400")
        void sizeOverMax_returns400() {
            // UUIDv4 (#948)
            final String sessionId = "550e8400-e29b-41d4-a716-11ee5e554a0a";
            given()
                    .header("X-Session-Id", sessionId)
                    .queryParam("size", 101)
                    .when()
                    .get("/api/v1/sessions/" + sessionId + "/likes")
                    .then()
                    .statusCode(HttpStatus.BAD_REQUEST.value());
        }

        /**
         * 곡이 삭제된 (orphan songId 참조) 좋아요는 응답에서 제외된다. totalCount 는 count 기준이라 차이날 수 있음을 검증.
         */
        @Test
        @DisplayName("좋아요 대상 곡이 삭제된 경우 → 응답 배열에서 제외(빈 응답 + totalCount=1)")
        void orphanSongId_isFilteredFromResponse() {
            // UUIDv4 (#948)
            final String sessionId = "550e8400-e29b-41d4-a716-11ee5e554a0b";
            final Long orphanSongId = songRepository.save(Song.builder()
                    .title("삭제될 곡").artist("가수").keyOriginal(MusicalKey.C_MAJOR)
                    .metadataSource(MetadataSource.MANUAL_SEED).build()).getId();
            likeRepository.save(Like.create(sessionId, orphanSongId));
            songRepository.deleteById(orphanSongId);

            given()
                    .header("X-Session-Id", sessionId)
                    .when()
                    .get("/api/v1/sessions/" + sessionId + "/likes")
                    .then()
                    .statusCode(HttpStatus.OK.value())
                    .body("responses", hasSize(0))
                    .body("totalCount", equalTo(1))
                    // hasNext 는 count 기반 — 다음 페이지가 있더라도 곡이 모두 삭제됐다면 빈 배열로 응답
                    .body("hasNext", is(equalTo(false)))
                    .body("responses[0]", nullValue());
        }

        private void sleepMillis(final long millis) {
            try {
                Thread.sleep(millis);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
