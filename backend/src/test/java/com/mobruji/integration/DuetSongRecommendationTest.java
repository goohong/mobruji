package com.mobruji.integration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
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
import com.mobruji.song.domain.VocalGender;
import com.mobruji.song.infrastructure.SongRepository;

import io.restassured.RestAssured;

/**
 * P-G 듀엣·함께 부르기형 추천(persona-expansion-social-emotional.md §2/§5) E2E.
 *
 * <p>{@code POST /api/v1/recommendations/duet} 가 ① persona=P-G + 곡별 "파트 분담" 안내를 노출하고 ② 큐레이션 듀엣곡
 * ({@code VocalGender.MIXED}) 우위 + 두 음역 동시 충족도로 강편향하며 ③ 같은 입력에 같은 결과(결정성)를 돌려주는지, fe 연동
 * 응답 계약(persona + requestId + 단일 추천과 같은 곡 형상 + partAssignmentReason)을 검증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DuetSongRecommendationTest {

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
        // 두 가수: 요청자(남성, 저음 48~64) + 파트너(여성, 고음 60~79). 합집합 음역 [48, 79] 로 후보를 만든다.
        // 듀엣 강편향(MIXED 우위 + 두 음역 동시 충족도) 검증을 위해 곡 음역을 두 파트에 잘/덜 맞게 섞고, 큐레이션 듀엣곡 1건을 MIXED 로 둔다.
        // 두 음역 동시 충족도 mismatch(낮은 파트 vs [48,64] + 높은 파트 vs [60,79]): MIXED=0, 딱맞듀엣=0, 조금높은=7, 높은곡=15, 낮은곡=18, 많이높은=23.
        songRepository.save(buildSong("듀엣곡", "가수A", MusicalKey.C_MAJOR, Mood.EMOTIONAL, 50, 76, VocalGender.MIXED));
        songRepository.save(buildSong("딱맞듀엣", "가수B", MusicalKey.D_MAJOR, Mood.GROOVY, 54, 70, null));
        songRepository.save(buildSong("조금높은", "가수C", MusicalKey.E_MAJOR, Mood.UPBEAT, 60, 80, null));
        songRepository.save(buildSong("높은곡", "가수D", MusicalKey.F_MAJOR, Mood.POWERFUL, 64, 84, null));
        songRepository.save(buildSong("낮은곡", "가수E", MusicalKey.G_MAJOR, Mood.CALM, 40, 60, null));
        songRepository.save(buildSong("많이높은", "가수F", MusicalKey.A_MAJOR, Mood.POWERFUL, 68, 88, null));
    }

    @Test
    @DisplayName("E2E (#1845): 듀엣 추천 → 201 + persona=P-G + MIXED 듀엣곡 우위 + 곡별 파트 분담 안내(남성/여성 파트) + requestId 노출")
    void e2e_duetReturnsMixedDominantWithPartAssignment() {
        final String createBody = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-44eeec0e2d01",
                  "voiceRangeLow": 48,
                  "voiceRangeHigh": 64,
                  "partnerVoiceRangeLow": 60,
                  "partnerVoiceRangeHigh": 79,
                  "gender": "MALE",
                  "partnerGender": "FEMALE"
                }
                """;

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(createBody)
                .when()
                .post("/api/v1/recommendations/duet")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .body("persona", equalTo("P-G"))
                // 단일 추천과 같은 경로로 영속 → requestId 노출(곡 피드백·재조회 경로 재사용).
                .body("requestId", notNullValue())
                .body("recommendations.size()", greaterThan(0))
                // 듀엣 강편향: 최상위 곡은 큐레이션 듀엣곡(MIXED)이다.
                .body("recommendations[0].recommendation.song.title", equalTo("듀엣곡"))
                // 최상위 곡의 파트 분담: 큐레이션 듀엣곡 + 남성(저음)/여성(고음) 파트 라벨.
                .body("recommendations[0].partAssignmentReason", containsString("듀엣곡"))
                .body("recommendations[0].partAssignmentReason", containsString("남성 파트"))
                .body("recommendations[0].partAssignmentReason", containsString("여성 파트"))
                // 모든 곡에 "파트 분담" 안내가 붙는다(설명 가능성).
                .body("recommendations.partAssignmentReason", everyItem(notNullValue()))
                // 단일 추천과 같은 곡 형상(결과 카드 렌더 재사용).
                .body("recommendations[0].recommendation.rankPosition", equalTo(1))
                .body("recommendations[0].recommendation.voiceFit", notNullValue());
    }

    @Test
    @DisplayName("E2E (#1845): limit 적용 + 두 음역 동시 충족도가 좋은 순으로 정렬(mismatch 비-감소)")
    void e2e_jointRangeFitOrdering() {
        final String createBody = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-44eeec0e2d02",
                  "voiceRangeLow": 48,
                  "voiceRangeHigh": 64,
                  "partnerVoiceRangeLow": 60,
                  "partnerVoiceRangeHigh": 79,
                  "limit": 5
                }
                """;

        final List<Integer> lowMidis = given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(createBody)
                .when()
                .post("/api/v1/recommendations/duet")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .body("recommendations.size()", equalTo(5))
                .extract().path("recommendations.recommendation.song.lowMidi");
        final List<Integer> highMidis = given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(createBody)
                .when()
                .post("/api/v1/recommendations/duet")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .extract().path("recommendations.recommendation.song.highMidi");

        // 두 음역 동시 충족도(mismatch) 가 비-감소(non-decreasing) 여야 한다(MIXED 듀엣곡도 mismatch 0 로 최상위라 단조 유지).
        int previousMismatch = -1;
        for (int i = 0; i < lowMidis.size(); i++) {
            final int mismatch = jointRangeMismatch(lowMidis.get(i), highMidis.get(i));
            Assertions.assertTrue(
                    mismatch >= previousMismatch,
                    "두 음역 동시 충족도가 비-감소여야 한다(듀엣 적합 우위 정렬): lows=" + lowMidis + " highs=" + highMidis);
            previousMismatch = mismatch;
        }
    }

    @Test
    @DisplayName("E2E (#1845): 같은 입력은 같은 결과(결정성) — 곡 순서 동일")
    void e2e_determinism() {
        final String createBody = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-44eeec0e2d03",
                  "voiceRangeLow": 48,
                  "voiceRangeHigh": 64,
                  "partnerVoiceRangeLow": 60,
                  "partnerVoiceRangeHigh": 79
                }
                """;

        final List<Integer> firstSongIds = extractSongIds(createBody);
        final List<Integer> secondSongIds = extractSongIds(createBody);

        Assertions.assertEquals(
                firstSongIds, secondSongIds,
                "같은 입력은 같은 곡 순서를 돌려줘야 한다(결정성): " + firstSongIds + " vs " + secondSongIds);
    }

    @Test
    @DisplayName("E2E (#1845): 파트너 음역(필수)이 누락되면 400 (듀엣 2인 음역 입력 계약)")
    void e2e_validationFailureOnMissingPartnerRange() {
        final String invalidBody = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-44eeec0e2d04",
                  "voiceRangeLow": 48,
                  "voiceRangeHigh": 64
                }
                """;

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(invalidBody)
                .when()
                .post("/api/v1/recommendations/duet")
                .then()
                .statusCode(HttpStatus.BAD_REQUEST.value())
                .body("fieldErrors.find { it.field == 'partnerVoiceRangeLow' }", notNullValue());
    }

    /**
     * 테스트용 두 음역 동시 충족도 — 서비스의 {@code jointRangeMismatch} 산식 거울(낮은 가수 [48,64], 높은 가수 [60,79]).
     * 곡 음역을 중간음에서 둘로 나눠 낮은 파트 vs 저음 가수, 높은 파트 vs 고음 가수 음역의 벗어난 반음 합.
     */
    private static int jointRangeMismatch(final int lowMidi, final int highMidi) {
        final int splitMidi = (lowMidi + highMidi) / 2;
        final int lowerMismatch = Math.max(0, 48 - lowMidi) + Math.max(0, splitMidi - 64);
        final int higherMismatch = Math.max(0, 60 - splitMidi) + Math.max(0, highMidi - 79);
        return lowerMismatch + higherMismatch;
    }

    private static List<Integer> extractSongIds(final String createBody) {
        return given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(createBody)
                .when()
                .post("/api/v1/recommendations/duet")
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
            final int highMidi,
            final VocalGender vocalGender) {
        return Song.builder()
                .title(title).artist(artist).releaseYear(2020)
                .keyOriginal(key).bpm(120).mood(mood)
                .lowMidi(lowMidi).highMidi(highMidi)
                .difficulty(Song.deriveDifficulty(lowMidi, highMidi))
                .vocalGender(vocalGender)
                .language("ko").genre("발라드")
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
    }
}
