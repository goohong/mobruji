package com.mobruji.integration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RecommendationIntegrationTest {

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
        // 곡 5개 시드 (다양성 후처리 검증 가능)
        songRepository.save(buildSong("벚꽃 엔딩", "버스커 버스커", MusicalKey.A_MAJOR, Mood.EMOTIONAL, "발라드"));
        songRepository.save(buildSong("Dynamite", "BTS", MusicalKey.E_MAJOR, Mood.UPBEAT, "댄스"));
        songRepository.save(buildSong("Spring Day", "BTS", MusicalKey.D_SHARP_MAJOR, Mood.EMOTIONAL, "발라드"));
        songRepository.save(buildSong("Eight", "IU", MusicalKey.A_MAJOR, Mood.NOSTALGIC, "팝"));
        songRepository.save(buildSong("Lilac", "IU", MusicalKey.D_MAJOR, Mood.UPBEAT, "팝"));
    }

    @Test
    @DisplayName("E2E: 추천 요청 → 결과 N개 + GET 재조회 일치")
    void e2e_createThenRead() {
        // sessionId 는 UUIDv4 (#948 SessionIdPatterns 강제)
        final String createBody = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-11eeec0e2e01",
                  "voiceRangeLow": 55,
                  "voiceRangeHigh": 75,
                  "mood": "UPBEAT"
                }
                """;

        // POST → 201. breakdown 5신호도 함께 노출 (spec #145 Spotify "Why this song?" UX)
        final Integer requestId = given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(createBody)
                .when()
                .post("/api/v1/recommendations")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .body("requestId", notNullValue())
                .body("recommendations.size()", greaterThan(0))
                .body("recommendations.size()", lessThanOrEqualTo(10))
                .body("recommendations[0].rankPosition", equalTo(1))
                .body("recommendations[0].matchReason", notNullValue())
                // 설명가능성(#1484): top-level voiceFit(0~1) + 짧은 한국어 사유 노출
                .body("recommendations[0].voiceFit", notNullValue())
                .body("recommendations[0].voiceFit", greaterThanOrEqualTo(0.0f))
                .body("recommendations[0].voiceFit", lessThanOrEqualTo(1.0f))
                .body("recommendations[0].voiceFitReason", notNullValue())
                .body("recommendations[0].breakdown", notNullValue())
                .body("recommendations[0].breakdown.keyMatch", notNullValue())
                .body("recommendations[0].breakdown.rangeFit", notNullValue())
                .body("recommendations[0].breakdown.genreMatch", notNullValue())
                .body("recommendations[0].breakdown.moodMatch", notNullValue())
                .body("recommendations[0].breakdown.popularity", notNullValue())
                .extract().path("requestId");

        // GET 재조회 → 동일. 영속 엔티티에 breakdown 컬럼이 없으므로 재조회 경로의 breakdown은 null로 노출된다.
        given()
                .when()
                .get("/api/v1/recommendations/" + requestId)
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("requestId", equalTo(requestId))
                .body("recommendations.size()", greaterThan(0))
                // 재조회 경로는 breakdown 미영속 → voiceFit/voiceFitReason 도 null (#1484)
                .body("recommendations[0].voiceFit", nullValue())
                .body("recommendations[0].voiceFitReason", nullValue());
    }

    @Test
    @DisplayName("E2E (v2 #218): 응답 breakdown.tempoMatch가 [0,1] 범위 내 노출된다")
    void e2e_tempoMatchInResponse() {
        // given: preferredBpm 입력. 시드 곡 BPM은 buildSong 에서 120 (UPBEAT default와 비슷한 영역).
        final String createBody = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-11eeec0e2e02",
                  "voiceRangeLow": 55,
                  "voiceRangeHigh": 75,
                  "mood": "UPBEAT",
                  "preferredBpm": 120
                }
                """;

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(createBody)
                .when()
                .post("/api/v1/recommendations")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .body("recommendations[0].breakdown.tempoMatch", notNullValue())
                .body("recommendations[0].breakdown.tempoMatch",
                        org.hamcrest.Matchers.greaterThanOrEqualTo(0.0f))
                .body("recommendations[0].breakdown.tempoMatch",
                        org.hamcrest.Matchers.lessThanOrEqualTo(1.0f));
    }

    @Test
    @DisplayName("E2E (#1487): ageGroup 입력 시 201 + breakdown.generationFit가 (0,1] 범위로 노출된다")
    void e2e_ageGroupGenerationFitInResponse() {
        // given: 시드 곡 releaseYear=2020. ageGroup=TWENTIES 대표 시기=2015, tolerance=15
        //        → 거리 5 → generationFit = 1 - 5/15 ≈ 0.667 (> 0)
        final String createBody = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-11eeec0e2e04",
                  "voiceRangeLow": 55,
                  "voiceRangeHigh": 75,
                  "mood": "UPBEAT",
                  "ageGroup": "TWENTIES"
                }
                """;

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(createBody)
                .when()
                .post("/api/v1/recommendations")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .body("recommendations[0].breakdown.generationFit", notNullValue())
                .body("recommendations[0].breakdown.generationFit", greaterThan(0.0f))
                .body("recommendations[0].breakdown.generationFit", lessThanOrEqualTo(1.0f));
    }

    @Test
    @DisplayName("E2E (#1487): ageGroup 미입력 시 generationFit=0.0 (랭킹 무영향, 하위호환)")
    void e2e_noAgeGroupGenerationFitZero() {
        final String createBody = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-11eeec0e2e05",
                  "voiceRangeLow": 55,
                  "voiceRangeHigh": 75,
                  "mood": "UPBEAT"
                }
                """;

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(createBody)
                .when()
                .post("/api/v1/recommendations")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .body("recommendations[0].breakdown.generationFit", equalTo(0.0f));
    }

    @Test
    @DisplayName("E2E (#1494): 음역 보유 곡은 practiceDifficulty(난이도) + 최고음 사유가 응답에 노출된다")
    void e2e_practiceDifficultyExposedForRangedSong() {
        // given: 음역대를 가진 곡만 시드 (HARD: low 57 ~ high 81, span 24, 최고음 A5)
        recommendationRepository.deleteAll();
        recommendationRequestRepository.deleteAll();
        songRepository.deleteAll();
        songRepository.save(Song.builder()
                .title("도전곡").artist("도전가수").releaseYear(2020)
                .keyOriginal(MusicalKey.C_MAJOR).bpm(120).mood(Mood.UPBEAT)
                .language("ko").genre("발라드")
                .lowMidi(57).highMidi(81)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build());

        final String createBody = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-11eeec0e2e06",
                  "voiceRangeLow": 55,
                  "voiceRangeHigh": 82,
                  "mood": "UPBEAT"
                }
                """;

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(createBody)
                .when()
                .post("/api/v1/recommendations")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .body("recommendations[0].practiceDifficulty", equalTo("HARD"))
                .body("recommendations[0].practiceDifficultyReason", equalTo("최고음 A5, 고음·넓은 음역이라 도전적인 곡이에요"));
    }

    @Test
    @DisplayName("E2E (#1494): 음역 미보유 곡은 practiceDifficulty=null + graceful 사유로 처리된다")
    void e2e_practiceDifficultyGracefulForSongWithoutRange() {
        // 기본 시드(buildSong)는 lowMidi/highMidi 미설정 → difficulty null
        final String createBody = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-11eeec0e2e07",
                  "voiceRangeLow": 55,
                  "voiceRangeHigh": 75,
                  "mood": "UPBEAT"
                }
                """;

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(createBody)
                .when()
                .post("/api/v1/recommendations")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .body("recommendations[0].practiceDifficulty", nullValue())
                .body("recommendations[0].practiceDifficultyReason",
                        equalTo("아직 음역대 분석 정보가 없어 난이도를 가늠하기 어려워요"));
    }

    @Test
    @DisplayName("E2E (#1544): voiceFit 낮은 곡은 suggestedTranspose(반음) + 조옮김 후 voiceFit 이 노출된다")
    void e2e_transposeSuggestedForLowFitSong() {
        // given: A_MAJOR(root 69) 한 곡만 시드. 사용자 음역 [50,70](center 60)은 곡 키 중심(69)과 멀어 voiceFit 이 낮다.
        //        -6 반음 내리면 root 63 → 음역대 중심에 가까워져 적합도가 크게 오른다(0.4 임계 초과).
        recommendationRepository.deleteAll();
        recommendationRequestRepository.deleteAll();
        songRepository.deleteAll();
        songRepository.save(buildSong("높은키곡", "어떤가수", MusicalKey.A_MAJOR, Mood.UPBEAT, "발라드"));

        final String createBody = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-11eeec0e2e08",
                  "voiceRangeLow": 50,
                  "voiceRangeHigh": 70,
                  "mood": "UPBEAT"
                }
                """;

        final Integer requestId = given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(createBody)
                .when()
                .post("/api/v1/recommendations")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                // 원곡 voiceFit 은 낮음(< 0.4) → 조옮김 제안이 채워진다.
                .body("recommendations[0].voiceFit", lessThanOrEqualTo(0.4f))
                .body("recommendations[0].suggestedTranspose", equalTo(-6))
                .body("recommendations[0].transposedVoiceFit", notNullValue())
                // 조옮김 후 적합도는 원곡보다 높고(임계 초과) [0,1] 범위 안.
                .body("recommendations[0].transposedVoiceFit", greaterThan(0.4f))
                .body("recommendations[0].transposedVoiceFit", lessThanOrEqualTo(1.0f))
                .body("recommendations[0].suggestedTransposeReason", equalTo("6키 내려 부르면 음역대에 더 잘 맞아요"))
                .extract().path("requestId");

        // 재조회 경로는 breakdown 미영속 → 조옮김 필드도 null.
        given()
                .when()
                .get("/api/v1/recommendations/" + requestId)
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("recommendations[0].suggestedTranspose", nullValue())
                .body("recommendations[0].transposedVoiceFit", nullValue())
                .body("recommendations[0].suggestedTransposeReason", nullValue());
    }

    @Test
    @DisplayName("E2E (#1544): 원곡 그대로 음역대에 무난한 곡은 조옮김 제안이 없다(null)")
    void e2e_noTransposeWhenFitIsAdequate() {
        // given: E_MAJOR(root 64) 한 곡. 사용자 음역 [55,75](center 65)은 곡 키 중심과 거의 일치 → voiceFit 높음.
        recommendationRepository.deleteAll();
        recommendationRequestRepository.deleteAll();
        songRepository.deleteAll();
        songRepository.save(buildSong("딱맞는곡", "어떤가수", MusicalKey.E_MAJOR, Mood.UPBEAT, "발라드"));

        final String createBody = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-11eeec0e2e09",
                  "voiceRangeLow": 55,
                  "voiceRangeHigh": 75,
                  "mood": "UPBEAT"
                }
                """;

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(createBody)
                .when()
                .post("/api/v1/recommendations")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .body("recommendations[0].voiceFit", greaterThanOrEqualTo(0.4f))
                .body("recommendations[0].suggestedTranspose", nullValue())
                .body("recommendations[0].transposedVoiceFit", nullValue())
                .body("recommendations[0].suggestedTransposeReason", nullValue());
    }

    @Test
    @DisplayName("E2E: 없는 추천 ID는 404")
    void e2e_notFound() {
        given()
                .when()
                .get("/api/v1/recommendations/999999")
                .then()
                .statusCode(HttpStatus.NOT_FOUND.value());
    }

    @Test
    @DisplayName("E2E (directive 1509473362760695818): 400 응답 body 에 message + fieldErrors 노출 — sessionId 마스킹")
    void e2e_validationFailure_returnsActionableBody() {
        // sessionId 가 UUIDv4 가 아니면 RecommendationCreateRequest 의 @Pattern 위반.
        final String invalidBody = """
                {
                  "sessionId": "sess_1709000000_abc123",
                  "voiceRangeLow": 48,
                  "voiceRangeHigh": 72,
                  "mood": "UPBEAT"
                }
                """;

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(invalidBody)
                .when()
                .post("/api/v1/recommendations")
                .then()
                .statusCode(HttpStatus.BAD_REQUEST.value())
                .body("status", equalTo(400))
                .body("error", equalTo("Bad Request"))
                .body("message", notNullValue())
                .body("fieldErrors.find { it.field == 'sessionId' }", notNullValue())
                // 보안 요건: sessionId 원문 노출 금지 — rejectedValue 마스킹 확인.
                .body("fieldErrors.find { it.field == 'sessionId' }.rejectedValue", equalTo("***"));
    }

    @Test
    @DisplayName("E2E: voiceRangeHigh 가 spec(@Max 119) 초과 → 400 + field/rejectedValue 노출")
    void e2e_voiceRangeOutOfBounds_returnsFieldError() {
        final String outOfRangeBody = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-11eeec0e2eaa",
                  "voiceRangeLow": 48,
                  "voiceRangeHigh": 200
                }
                """;

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(outOfRangeBody)
                .when()
                .post("/api/v1/recommendations")
                .then()
                .statusCode(HttpStatus.BAD_REQUEST.value())
                .body("fieldErrors.find { it.field == 'voiceRangeHigh' }", notNullValue())
                .body("fieldErrors.find { it.field == 'voiceRangeHigh' }.rejectedValue", equalTo(200));
    }

    @Test
    @DisplayName("E2E: 같은 아티스트 ≤ 2 다양성 후처리 작동")
    void e2e_diversityRespectsArtistCap() {
        // BTS 2곡, IU 2곡 시드. 모두 점수 높게 잡혀도 결과 내 BTS 곡은 최대 2 (cap 2)
        final String createBody = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-11eeec0e2e03",
                  "voiceRangeLow": 50,
                  "voiceRangeHigh": 80,
                  "mood": "EMOTIONAL"
                }
                """;

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(createBody)
                .when()
                .post("/api/v1/recommendations")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                // BTS 곡이 결과에 포함되더라도 최대 2개 (cap)
                .body("recommendations.findAll { it.song.artist == 'BTS' }.size()", lessThanOrEqualTo(2));
    }

    private static Song buildSong(
            final String title, final String artist, final MusicalKey key, final Mood mood, final String genre) {
        return Song.builder()
                .title(title).artist(artist).releaseYear(2020)
                .keyOriginal(key).bpm(120).mood(mood)
                .language("ko").genre(genre)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
    }
}
