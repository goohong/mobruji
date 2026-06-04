package com.mobruji.integration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import com.mobruji.recommendation.infrastructure.RecommendationRepository;
import com.mobruji.recommendation.infrastructure.RecommendationRequestRepository;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

import io.restassured.RestAssured;

/**
 * P-E 안전곡형 추천(persona-expansion-social-emotional.md §2/§5) E2E.
 *
 * <p>{@code POST /api/v1/recommendations/safe} 가 ① persona=P-E + 곡별 "안심 포인트"를 노출하고 ② 쉬운 난이도(EASY)
 * 우위로 강편향하며 ③ 같은 입력에 같은 결과(결정성)를 돌려주는지, fe(#1600) 연동 응답 계약(persona + requestId + 단일 추천과
 * 같은 곡 형상 + safetyReason)을 검증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SafeSongRecommendationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private SongRepository songRepository;

    @Autowired
    private RecommendationRequestRepository recommendationRequestRepository;

    @Autowired
    private RecommendationRepository recommendationRepository;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        recommendationRepository.deleteAll();
        recommendationRequestRepository.deleteAll();
        songRepository.deleteAll();
        // 난이도 자동 분류(Song.deriveDifficulty): highMidi<71=EASY, 71~75=NORMAL, >=76 또는 span>=17=HARD.
        // EASY 우위 검증을 위해 EASY/NORMAL/HARD 를 섞어 시드한다(사용자 음역 55~72 안에서 모두 후보로 닿게).
        songRepository.save(buildSong("쉬운곡1", "가수A", MusicalKey.C_MAJOR, Mood.CALM, 55, 66));
        songRepository.save(buildSong("쉬운곡2", "가수B", MusicalKey.D_MAJOR, Mood.NOSTALGIC, 57, 68));
        songRepository.save(buildSong("보통곡1", "가수C", MusicalKey.E_MAJOR, Mood.GROOVY, 60, 73));
        songRepository.save(buildSong("보통곡2", "가수D", MusicalKey.F_MAJOR, Mood.UPBEAT, 61, 74));
        songRepository.save(buildSong("어려운곡1", "가수E", MusicalKey.G_MAJOR, Mood.POWERFUL, 64, 78));
        songRepository.save(buildSong("어려운곡2", "가수F", MusicalKey.A_MAJOR, Mood.UPBEAT, 66, 80));
    }

    @Test
    @DisplayName("E2E (#1598): 안전곡 추천 → 201 + persona=P-E + EASY 우위 + 곡별 안심 포인트 + requestId 노출")
    void e2e_safeReturnsEasyDominantWithSafetyReason() {
        final String createBody = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-22eeec0e2d01",
                  "voiceRangeLow": 55,
                  "voiceRangeHigh": 72
                }
                """;

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(createBody)
                .when()
                .post("/api/v1/recommendations/safe")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .body("persona", equalTo("P-E"))
                // 단일 추천과 같은 경로로 영속 → requestId 노출(곡 피드백·재조회 경로 재사용).
                .body("requestId", notNullValue())
                .body("recommendations.size()", greaterThan(0))
                // 안전곡 강편향: 최상위 곡은 쉬운 난이도(EASY)여야 한다(EASY 풀이 충분).
                .body("recommendations[0].recommendation.practiceDifficulty", equalTo("EASY"))
                // 모든 곡에 "안심 포인트"가 붙는다(설명 가능성).
                .body("recommendations.safetyReason", everyItem(notNullValue()))
                // 단일 추천과 같은 곡 형상(결과 카드 렌더 재사용).
                .body("recommendations[0].recommendation.song.title", notNullValue())
                .body("recommendations[0].recommendation.rankPosition", equalTo(1))
                .body("recommendations[0].recommendation.voiceFit", notNullValue());
    }

    @Test
    @DisplayName("E2E (#1598): limit 적용 + EASY 가 NORMAL/HARD 보다 앞선다(난이도 우위 정렬)")
    void e2e_limitAndEasyOrdering() {
        final String createBody = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-22eeec0e2d02",
                  "voiceRangeLow": 55,
                  "voiceRangeHigh": 72,
                  "limit": 4
                }
                """;

        final List<String> difficulties = given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(createBody)
                .when()
                .post("/api/v1/recommendations/safe")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .body("recommendations.size()", equalTo(4))
                .extract().path("recommendations.recommendation.practiceDifficulty");

        // 난이도 우위 정렬: EASY 가 앞, 그 뒤 NORMAL/HARD. 한 번 비-EASY 가 나오면 그 뒤로 EASY 가 다시 나오면 안 된다.
        boolean nonEasySeen = false;
        for (final String difficulty : difficulties) {
            if ("EASY".equals(difficulty)) {
                Assertions.assertFalse(
                        nonEasySeen, "EASY 가 비-EASY 뒤에 나오면 안 된다(난이도 우위 정렬): " + difficulties);
            } else {
                nonEasySeen = true;
            }
        }
        // 시드에 EASY 가 2곡 있으므로 최상위 2곡은 EASY 여야 한다.
        Assertions.assertEquals("EASY", difficulties.get(0), "최상위 곡은 EASY: " + difficulties);
        Assertions.assertEquals("EASY", difficulties.get(1), "차상위 곡은 EASY: " + difficulties);
    }

    @Test
    @DisplayName("E2E (#1598): 같은 입력은 같은 결과(결정성) — 곡 순서 동일")
    void e2e_determinism() {
        final String createBody = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-22eeec0e2d03",
                  "voiceRangeLow": 55,
                  "voiceRangeHigh": 72
                }
                """;

        final List<Integer> firstSongIds = extractSongIds(createBody);
        final List<Integer> secondSongIds = extractSongIds(createBody);

        Assertions.assertEquals(
                firstSongIds, secondSongIds, "같은 입력은 같은 곡 순서를 돌려줘야 한다(결정성): " + firstSongIds + " vs " + secondSongIds);
    }

    @Test
    @DisplayName("E2E (#1598): sessionId 가 UUIDv4 가 아니면 400 (단일 추천과 같은 검증 일관성)")
    void e2e_validationFailure() {
        final String invalidBody = """
                {
                  "sessionId": "not-a-uuid",
                  "voiceRangeLow": 55,
                  "voiceRangeHigh": 72
                }
                """;

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(invalidBody)
                .when()
                .post("/api/v1/recommendations/safe")
                .then()
                .statusCode(HttpStatus.BAD_REQUEST.value())
                .body("fieldErrors.find { it.field == 'sessionId' }", notNullValue());
    }

    private static List<Integer> extractSongIds(final String createBody) {
        return given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(createBody)
                .when()
                .post("/api/v1/recommendations/safe")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .extract().path("recommendations.recommendation.song.id");
    }

    private static Song buildSong(
            final String title,
            final String artist,
            final MusicalKey key,
            final Mood mood,
            final int lowMidi,
            final int highMidi) {
        return Song.builder()
                .title(title).artist(artist).releaseYear(2020)
                .keyOriginal(key).bpm(90).mood(mood)
                .lowMidi(lowMidi).highMidi(highMidi)
                .difficulty(Song.deriveDifficulty(lowMidi, highMidi))
                .language("ko").genre("발라드")
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
    }
}
