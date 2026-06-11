package com.mobruji.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

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
 * 추천 결정성 회귀 테스트.
 *
 * <p>spec: {@code docs/features/recommendation-algorithm-v1.md} §3 비기능 — 결정성
 * (같은 입력 → 같은 결과). 동일 페이로드 두 번 호출 시 곡 ID 순서·score·breakdown 영향이
 * 일치하는지를 RestAssured E2E로 검증한다.
 *
 * <p>entropy 보존(다른 입력 → 다른 seed) 단정은 단위 레이어({@link
 * com.mobruji.recommendation.application.SeedDeriverTest})로 이동했다 (#299). E2E는
 * 곡 시드 + 가중치 미세 변경에 flaky 하므로 결정성 회귀만 가드한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RecommendationDeterminismTest {

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
        // 곡 8개 시드 — jitter의 영향이 드러날 정도로 동순위 후보가 다수 있어야 회귀 신호가 강해진다.
        songRepository.save(buildSong("벚꽃 엔딩", "버스커 버스커", MusicalKey.A_MAJOR, Mood.EMOTIONAL, "발라드"));
        songRepository.save(buildSong("Dynamite", "BTS", MusicalKey.E_MAJOR, Mood.UPBEAT, "댄스"));
        songRepository.save(buildSong("Spring Day", "BTS", MusicalKey.D_SHARP_MAJOR, Mood.EMOTIONAL, "발라드"));
        songRepository.save(buildSong("Eight", "IU", MusicalKey.A_MAJOR, Mood.NOSTALGIC, "팝"));
        songRepository.save(buildSong("Lilac", "IU", MusicalKey.D_MAJOR, Mood.UPBEAT, "팝"));
        songRepository.save(buildSong("좋은 날", "IU", MusicalKey.C_MAJOR, Mood.UPBEAT, "팝"));
        songRepository.save(buildSong("Butter", "BTS", MusicalKey.G_MAJOR, Mood.UPBEAT, "댄스"));
        songRepository.save(buildSong("취중진담", "전람회", MusicalKey.F_MAJOR, Mood.EMOTIONAL, "발라드"));
    }

    @Test
    @DisplayName("결정성: 같은 입력 두 번 호출 → 결과 song ID 순서 동일")
    void determinism_sameInput_yieldsSameOrder() {
        // given
        final String payload = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-11eedee04e01",
                  "voiceRangeLow": 55,
                  "voiceRangeHigh": 75,
                  "mood": "UPBEAT"
                }
                """;
        // when
        final List<Integer> firstOrder = postAndExtractSongIds(payload);
        final List<Integer> secondOrder = postAndExtractSongIds(payload);
        // then
        assertThat(firstOrder).isEqualTo(secondOrder);
    }

    @Test
    @DisplayName("결정성 회귀 가드 (#145): breakdown 노출이 score/순서에 영향이 없다 — 같은 입력 두 번 → 1위 score 동일")
    void determinism_breakdownDoesNotAffectScore() {
        // given
        final String payload = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-11eedee04e02",
                  "voiceRangeLow": 55,
                  "voiceRangeHigh": 75,
                  "mood": "UPBEAT"
                }
                """;
        // when
        final Float firstScore = given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(payload)
                .when().post("/api/v1/recommendations")
                .then().statusCode(HttpStatus.CREATED.value())
                .extract().jsonPath().getFloat("recommendations[0].score");
        final Float secondScore = given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(payload)
                .when().post("/api/v1/recommendations")
                .then().statusCode(HttpStatus.CREATED.value())
                .extract().jsonPath().getFloat("recommendations[0].score");
        // then: SeedDeriver 입력이 변함 없으면 score도 그대로
        assertThat(firstScore).isEqualTo(secondScore);
    }

    @Test
    @DisplayName("v2 결정성 (#218): 같은 preferredBpm 두 번 → 같은 결과 (seed 안정성)")
    void determinism_samePreferredBpm_yieldsSameOrder() {
        // given
        final String payload = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-11eedee04e03",
                  "voiceRangeLow": 55,
                  "voiceRangeHigh": 75,
                  "mood": "UPBEAT",
                  "preferredBpm": 128
                }
                """;
        // when
        final List<Integer> firstOrder = postAndExtractSongIds(payload);
        final List<Integer> secondOrder = postAndExtractSongIds(payload);
        // then
        assertThat(firstOrder).isEqualTo(secondOrder);
    }

    @Test
    @DisplayName("v2 entropy (#218): 다른 preferredBpm → 적어도 순서/점수가 달라진다 (tempoMatch 입력 영향)")
    void determinism_differentPreferredBpm_yieldsDifferentScore() {
        // given: 같은 sessionId/voiceRange, preferredBpm 만 다름
        final String payloadFast = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-11eedee04e04",
                  "voiceRangeLow": 55,
                  "voiceRangeHigh": 75,
                  "mood": "UPBEAT",
                  "preferredBpm": 130
                }
                """;
        final String payloadSlow = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-11eedee04e04",
                  "voiceRangeLow": 55,
                  "voiceRangeHigh": 75,
                  "mood": "UPBEAT",
                  "preferredBpm": 60
                }
                """;
        // when
        final Float fastTop = given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(payloadFast)
                .when().post("/api/v1/recommendations")
                .then().statusCode(HttpStatus.CREATED.value())
                .extract().jsonPath().getFloat("recommendations[0].breakdown.tempoMatch");
        final Float slowTop = given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(payloadSlow)
                .when().post("/api/v1/recommendations")
                .then().statusCode(HttpStatus.CREATED.value())
                .extract().jsonPath().getFloat("recommendations[0].breakdown.tempoMatch");
        // then: 곡 BPM 120 시드 — fast(130) 거리 10 → 0.75, slow(60) 거리 60 → 0.0 (tolerance 40 초과)
        assertThat(fastTop).isNotEqualTo(slowTop);
    }

    // NOTE (#299): "다른 sessionId → 다른 곡 순서" entropy 단정은 SeedDeriverTest 로 이동.
    // E2E 레이어는 곡 시드 8개 + 가중치 미세 변경에 flaky 했고, spec §7 테스트 전략(단위/통합/E2E
    // 3-레이어 분리)에 따라 entropy 단정의 단일 진실 레이어는 단위 테스트로 둔다. E2E는 결정성
    // 회귀 가드(같은 입력 4 케이스)만 책임진다.

    private List<Integer> postAndExtractSongIds(final String payload) {
        return given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(payload)
                .when()
                .post("/api/v1/recommendations")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .extract().jsonPath().getList("recommendations.song.id", Integer.class);
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
