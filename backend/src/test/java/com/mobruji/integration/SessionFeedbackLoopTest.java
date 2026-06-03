package com.mobruji.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

import java.util.List;

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

import com.mobruji.recommendation.infrastructure.RecommendationRepository;
import com.mobruji.recommendation.infrastructure.RecommendationRequestRepository;
import com.mobruji.recommendation.infrastructure.SessionFeedbackRepository;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

import io.restassured.RestAssured;

/**
 * 추천 피드백 루프 E2E(#1545 recommendation-feedback-loop.md §7).
 *
 * <p>검증 포인트:
 * <ul>
 * <li>반응 기록(upsert) → 조회 노출 → like→pass 재기록 → 최신 반응 회신.</li>
 * <li>{@code next} 결합: 세션 PASS 곡은 결과에서 제외, LIKE 곡은 시드로 편입(자동 제외). {@code useSessionFeedback=false}
 * 면 무시(하위호환).</li>
 * <li>콜드스타트: 반응 0건 {@code next} 는 결정적이며 기존 추천과 동일.</li>
 * <li>인증: {@code X-Session-Id} 불일치/누락 401.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SessionFeedbackLoopTest {

    @LocalServerPort
    private int port;

    @Autowired
    private SongRepository songRepository;

    @Autowired
    private SessionFeedbackRepository sessionFeedbackRepository;

    @Autowired
    private RecommendationRepository recommendationRepository;

    @Autowired
    private RecommendationRequestRepository recommendationRequestRepository;

    private Long seedSongId;
    private Long passSongId;
    private Long likeSongId;
    private Long otherSongId;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        recommendationRepository.deleteAll();
        recommendationRequestRepository.deleteAll();
        sessionFeedbackRepository.deleteAll();
        songRepository.deleteAll();
        seedSongId = songRepository.save(
                buildSong("부른 곡", "가수A", MusicalKey.C_MAJOR, Mood.UPBEAT)).getId();
        passSongId = songRepository.save(
                buildSong("패스한 곡", "가수B", MusicalKey.D_MAJOR, Mood.NOSTALGIC)).getId();
        likeSongId = songRepository.save(
                buildSong("좋아요한 곡", "가수C", MusicalKey.E_MAJOR, Mood.EMOTIONAL)).getId();
        otherSongId = songRepository.save(
                buildSong("그 외 곡", "가수D", MusicalKey.A_MAJOR, Mood.UPBEAT)).getId();
        songRepository.save(buildSong("그 외 곡2", "가수E", MusicalKey.F_MAJOR, Mood.EMOTIONAL));
    }

    @Test
    @DisplayName("E2E: 반응 기록(LIKE) → 조회 1건 → 재기록(PASS) → 조회 최신 반응 회신")
    void e2e_recordAndUpsert_succeeds() {
        final String sessionId = "550e8400-e29b-41d4-a716-11ee5feed101";

        // 1) POST LIKE → 200 + reaction=LIKE
        recordFeedback(sessionId, seedSongId, "LIKE")
                .statusCode(HttpStatus.OK.value())
                .body("songId", equalTo(seedSongId.intValue()))
                .body("reaction", equalTo("LIKE"));

        // 2) GET → 1건 + reaction=LIKE
        given()
                .header("X-Session-Id", sessionId)
                .when().get("/api/v1/sessions/" + sessionId + "/feedback")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("responses", hasSize(1))
                .body("responses[0].songId", equalTo(seedSongId.intValue()))
                .body("responses[0].reaction", equalTo("LIKE"))
                .body("totalCount", equalTo(1))
                .body("hasNext", equalTo(false));

        // 3) 같은 곡 PASS 재기록 → upsert (toggle 아님)
        recordFeedback(sessionId, seedSongId, "PASS")
                .statusCode(HttpStatus.OK.value())
                .body("reaction", equalTo("PASS"));

        // 4) GET → 여전히 1건, 최신 반응 PASS
        given()
                .header("X-Session-Id", sessionId)
                .when().get("/api/v1/sessions/" + sessionId + "/feedback")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("responses", hasSize(1))
                .body("responses[0].reaction", equalTo("PASS"))
                .body("totalCount", equalTo(1));
    }

    @Test
    @DisplayName("E2E: 존재하지 않는 songId 기록 → 404")
    void e2e_unknownSong_returns404() {
        final String sessionId = "550e8400-e29b-41d4-a716-11ee5feed404";
        recordFeedback(sessionId, 999999L, "LIKE")
                .statusCode(HttpStatus.NOT_FOUND.value());
    }

    @Nested
    @DisplayName("next 결합 (spec §5-3)")
    class NextCombination {

        @Test
        @DisplayName("세션 PASS 곡은 next 결과에서 제외되고, useSessionFeedback=false 면 다시 포함된다")
        void passSong_excludedWhenFeedbackEnabled() {
            final String sessionId = "550e8400-e29b-41d4-a716-11ee5feed201";
            recordFeedback(sessionId, passSongId, "PASS").statusCode(HttpStatus.OK.value());

            // 기본(useSessionFeedback=true) → PASS 곡 제외
            final List<Integer> withFeedback = postNext(
                    """
                            {"sessionId":"%s","seedSongIds":[%d]}
                            """.formatted(sessionId, seedSongId));
            assertThat(withFeedback).doesNotContain(passSongId.intValue());

            // useSessionFeedback=false → 세션 반응 무시 → PASS 곡 다시 포함(하위호환)
            final List<Integer> withoutFeedback = postNext(
                    """
                            {"sessionId":"%s","seedSongIds":[%d],"useSessionFeedback":false}
                            """.formatted(sessionId, seedSongId));
            assertThat(withoutFeedback).contains(passSongId.intValue());
        }

        @Test
        @DisplayName("세션 LIKE 곡은 시드로 편입되어 next 결과에서 자동 제외된다")
        void likeSong_treatedAsSeed() {
            final String sessionId = "550e8400-e29b-41d4-a716-11ee5feed202";
            recordFeedback(sessionId, likeSongId, "LIKE").statusCode(HttpStatus.OK.value());

            final List<Integer> withFeedback = postNext(
                    """
                            {"sessionId":"%s","seedSongIds":[%d]}
                            """.formatted(sessionId, seedSongId));
            // LIKE 곡은 선호 시드로 합류 → 부른 곡과 동일하게 결과에서 제외
            assertThat(withFeedback).doesNotContain(likeSongId.intValue());

            final List<Integer> withoutFeedback = postNext(
                    """
                            {"sessionId":"%s","seedSongIds":[%d],"useSessionFeedback":false}
                            """.formatted(sessionId, seedSongId));
            assertThat(withoutFeedback).contains(likeSongId.intValue());
        }

        @Test
        @DisplayName("콜드스타트: 반응 0건 next 는 결정적이며 useSessionFeedback 플래그와 무관하게 동일")
        void coldStart_isBackwardCompatible() {
            final String sessionId = "550e8400-e29b-41d4-a716-11ee5feed203";
            final String payloadTrue = """
                    {"sessionId":"%s","seedSongIds":[%d]}
                    """.formatted(sessionId, seedSongId);
            final String payloadFalse = """
                    {"sessionId":"%s","seedSongIds":[%d],"useSessionFeedback":false}
                    """.formatted(sessionId, seedSongId);

            final List<Integer> first = postNext(payloadTrue);
            final List<Integer> second = postNext(payloadTrue);
            final List<Integer> disabled = postNext(payloadFalse);

            assertThat(first).isEqualTo(second);
            assertThat(first).isEqualTo(disabled);
            assertThat(first).contains(otherSongId.intValue());
        }
    }

    @Nested
    @DisplayName("session-bound 인증 (spec §5-2 / ADR-0011)")
    class SessionBoundAuth {

        @Test
        @DisplayName("기록: X-Session-Id 헤더 누락 → 401 (저장 차단)")
        void record_missingHeader_returns401() {
            final String sessionId = "550e8400-e29b-41d4-a716-11ee5feed301";
            given()
                    .contentType(MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"sessionId":"%s","songId":%d,"reaction":"LIKE"}
                            """.formatted(sessionId, seedSongId))
                    .when().post("/api/v1/sessions/" + sessionId + "/feedback")
                    .then().statusCode(HttpStatus.UNAUTHORIZED.value());
            assertThat(sessionFeedbackRepository.countBySessionId(sessionId)).isZero();
        }

        @Test
        @DisplayName("조회: X-Session-Id 와 path sessionId 불일치 → 401, 타 세션 반응 미노출")
        void read_mismatchedHeader_returns401() {
            final String sessionA = "550e8400-e29b-41d4-a716-11ee5feed302";
            final String sessionB = "550e8400-e29b-41d4-a716-11ee5feed303";
            recordFeedback(sessionA, seedSongId, "LIKE").statusCode(HttpStatus.OK.value());

            given()
                    .header("X-Session-Id", sessionB)
                    .when().get("/api/v1/sessions/" + sessionA + "/feedback")
                    .then()
                    .statusCode(HttpStatus.UNAUTHORIZED.value())
                    .body("message", not(org.hamcrest.Matchers.containsString(sessionA)));
        }

        @Test
        @DisplayName("조회: 일치하지만 반응 0건 → 200 + 빈 배열 (404 아님)")
        void read_matchedEmpty_returns200() {
            final String sessionId = "550e8400-e29b-41d4-a716-11ee5feed304";
            given()
                    .header("X-Session-Id", sessionId)
                    .when().get("/api/v1/sessions/" + sessionId + "/feedback")
                    .then()
                    .statusCode(HttpStatus.OK.value())
                    .body("responses", hasSize(0))
                    .body("totalCount", equalTo(0))
                    .body("hasNext", equalTo(false));
        }
    }

    private io.restassured.response.ValidatableResponse recordFeedback(
            final String sessionId, final Long songId, final String reaction) {
        return given()
                .header("X-Session-Id", sessionId)
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body("""
                        {"sessionId":"%s","songId":%d,"reaction":"%s"}
                        """.formatted(sessionId, songId, reaction))
                .when().post("/api/v1/sessions/" + sessionId + "/feedback")
                .then();
    }

    private List<Integer> postNext(final String payload) {
        return given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(payload)
                .when().post("/api/v1/recommendations/next")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .extract().jsonPath().getList("recommendations.song.id", Integer.class);
    }

    private static Song buildSong(
            final String title, final String artist, final MusicalKey key, final Mood mood) {
        return Song.builder()
                .title(title).artist(artist).releaseYear(2020)
                .keyOriginal(key).bpm(120).mood(mood)
                .language("ko").genre("팝")
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
    }
}
