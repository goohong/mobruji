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
 * P-F 과시·킬링파트형 추천(persona-expansion-social-emotional.md §2/§5) E2E.
 *
 * <p>{@code POST /api/v1/recommendations/showoff} 가 ① persona=P-F + 곡별 "킬링파트 안내"를 노출하고 ② 사용자 음역 천장에
 * 가까운 고음 곡(킬링파트 fallback) + 어려운 난이도(HARD)로 강편향하며 ③ 같은 입력에 같은 결과(결정성)를 돌려주는지, fe 연동
 * 응답 계약(persona + requestId + 단일 추천과 같은 곡 형상 + killingPartReason)을 검증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ShowoffSongRecommendationTest {

    private static final int USER_VOICE_HIGH = 76;

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
        // 난이도 자동 분류(Song.deriveDifficulty): highMidi>=76 또는 span>=17=HARD, 71~75=NORMAL, <71=EASY.
        // 과시 강편향(천장 근접 + HARD 우위) 검증을 위해 곡 천장(highMidi)을 사용자 천장(76)에서 멀고 가깝게 섞어 시드한다.
        songRepository.save(buildSong("킬링파트1", "가수A", MusicalKey.C_MAJOR, Mood.POWERFUL, 60, 76)); // HARD, 천장거리 0
        songRepository.save(buildSong("킬링파트2", "가수B", MusicalKey.D_MAJOR, Mood.UPBEAT, 62, 78)); // HARD, 천장거리 2
        songRepository.save(buildSong("보통곡", "가수C", MusicalKey.E_MAJOR, Mood.GROOVY, 60, 73)); // NORMAL, 천장거리 3
        songRepository.save(buildSong("고음곡", "가수D", MusicalKey.F_MAJOR, Mood.POWERFUL, 64, 80)); // HARD, 천장거리 4
        songRepository.save(buildSong("쉬운곡1", "가수E", MusicalKey.G_MAJOR, Mood.CALM, 57, 68)); // EASY, 천장거리 8
        songRepository.save(buildSong("쉬운곡2", "가수F", MusicalKey.A_MAJOR, Mood.NOSTALGIC, 55, 66)); // EASY, 천장거리 10
    }

    @Test
    @DisplayName("E2E (#1842): 과시 추천 → 201 + persona=P-F + 천장 근접 고음·HARD 우위 + 곡별 킬링파트 안내 + requestId 노출")
    void e2e_showoffReturnsCeilingDominantWithKillingPartReason() {
        final String createBody = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-33eeec0e2d01",
                  "voiceRangeLow": 52,
                  "voiceRangeHigh": 76
                }
                """;

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(createBody)
                .when()
                .post("/api/v1/recommendations/showoff")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .body("persona", equalTo("P-F"))
                // 단일 추천과 같은 경로로 영속 → requestId 노출(곡 피드백·재조회 경로 재사용).
                .body("requestId", notNullValue())
                .body("recommendations.size()", greaterThan(0))
                // 과시 강편향: 최상위 곡은 사용자 천장(76)에 가장 가까운 고음 곡이며 어려운 난이도(HARD)다.
                .body("recommendations[0].recommendation.song.highMidi", equalTo(USER_VOICE_HIGH))
                .body("recommendations[0].recommendation.practiceDifficulty", equalTo("HARD"))
                // 모든 곡에 "킬링파트 안내"가 붙는다(설명 가능성).
                .body("recommendations.killingPartReason", everyItem(notNullValue()))
                // 단일 추천과 같은 곡 형상(결과 카드 렌더 재사용).
                .body("recommendations[0].recommendation.song.title", notNullValue())
                .body("recommendations[0].recommendation.rankPosition", equalTo(1))
                .body("recommendations[0].recommendation.voiceFit", notNullValue());
    }

    @Test
    @DisplayName("E2E (#1842): limit 적용 + 곡 천장이 사용자 천장에 가까운 순으로 정렬(천장 근접 우위)")
    void e2e_limitAndCeilingProximityOrdering() {
        final String createBody = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-33eeec0e2d02",
                  "voiceRangeLow": 52,
                  "voiceRangeHigh": 76,
                  "limit": 5
                }
                """;

        final List<Integer> highMidis = given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(createBody)
                .when()
                .post("/api/v1/recommendations/showoff")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .body("recommendations.size()", equalTo(5))
                .extract().path("recommendations.recommendation.song.highMidi");

        // 천장 근접 우위 정렬: |곡 천장 - 사용자 천장| 이 비-감소(non-decreasing) 여야 한다.
        int previousDistance = -1;
        for (final Integer highMidi : highMidis) {
            final int distance = Math.abs(highMidi - USER_VOICE_HIGH);
            Assertions.assertTrue(
                    distance >= previousDistance,
                    "곡 천장 근접도가 비-감소여야 한다(천장 근접 우위 정렬): " + highMidis);
            previousDistance = distance;
        }
        // 최상위 곡 천장은 사용자 천장(76)과 정확히 일치(거리 0)해야 한다.
        Assertions.assertEquals(
                USER_VOICE_HIGH, highMidis.get(0), "최상위 곡 천장은 사용자 천장에 가장 가까워야 한다: " + highMidis);
    }

    @Test
    @DisplayName("E2E (#1842): 같은 입력은 같은 결과(결정성) — 곡 순서 동일")
    void e2e_determinism() {
        final String createBody = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-33eeec0e2d03",
                  "voiceRangeLow": 52,
                  "voiceRangeHigh": 76
                }
                """;

        final List<Integer> firstSongIds = extractSongIds(createBody);
        final List<Integer> secondSongIds = extractSongIds(createBody);

        Assertions.assertEquals(
                firstSongIds, secondSongIds,
                "같은 입력은 같은 곡 순서를 돌려줘야 한다(결정성): " + firstSongIds + " vs " + secondSongIds);
    }

    @Test
    @DisplayName("E2E (#1842): sessionId 가 UUIDv4 가 아니면 400 (단일 추천과 같은 검증 일관성)")
    void e2e_validationFailure() {
        final String invalidBody = """
                {
                  "sessionId": "not-a-uuid",
                  "voiceRangeLow": 52,
                  "voiceRangeHigh": 76
                }
                """;

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(invalidBody)
                .when()
                .post("/api/v1/recommendations/showoff")
                .then()
                .statusCode(HttpStatus.BAD_REQUEST.value())
                .body("fieldErrors.find { it.field == 'sessionId' }", notNullValue());
    }

    private static List<Integer> extractSongIds(final String createBody) {
        return given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(createBody)
                .when()
                .post("/api/v1/recommendations/showoff")
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
                .keyOriginal(key).bpm(120).mood(mood)
                .lowMidi(lowMidi).highMidi(highMidi)
                .difficulty(Song.deriveDifficulty(lowMidi, highMidi))
                .language("ko").genre("발라드")
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
    }
}
