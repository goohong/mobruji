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
 * 분위기(mood) 입력 변별력 E2E (#1485).
 *
 * <p>버그(AS-IS): {@code moodMatch} 가 정확히 일치(1.0)/불일치(0.0) 이진 신호라, 요청 분위기와 일치하지 않는 곡은
 * 슬픈 발라드↔록 발라드↔댄스 처럼 결이 달라도 모두 0.0 으로 동률 → 분위기별 변별이 안 됐다.
 *
 * <p>fix(TO-BE): 분위기를 {@code (energy, brightness)} 좌표 거리 기반 연속 유사도로 환산하고 가중치도 상향(0.2→0.3).
 * 본 테스트는 키/BPM/장르가 모두 같고 mood 만 다른 곡들로, 변별 신호를 mood 로 격리해
 * (1) 요청 분위기를 바꾸면 1위 곡이 뒤바뀌고 (before/after 차이),
 * (2) 같은 요청 안에서도 가까운 분위기 곡이 먼 분위기 곡보다 상위에 오는지(gradient)를 RestAssured 로 검증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RecommendationMoodSensitivityTest {

    @LocalServerPort
    private int port;

    @Autowired
    private SongRepository songRepository;

    @Autowired
    private RecommendationRequestRepository recommendationRequestRepository;

    @Autowired
    private RecommendationRepository recommendationRepository;

    private long upbeatSongId;
    private long emotionalSongId;
    private long nostalgicSongId;
    private long calmSongId;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        recommendationRepository.deleteAll();
        recommendationRequestRepository.deleteAll();
        songRepository.deleteAll();
        // 키(C major)/BPM(120)/장르 동일, mood 만 다른 곡 — 변별 신호를 mood 로 격리.
        upbeatSongId = songRepository.save(
                buildSong("댄스 곡", "U 아티스트", Mood.UPBEAT)).getId();
        emotionalSongId = songRepository.save(
                buildSong("슬픈 발라드", "E 아티스트", Mood.EMOTIONAL)).getId();
        nostalgicSongId = songRepository.save(
                buildSong("추억의 곡", "N 아티스트", Mood.NOSTALGIC)).getId();
        calmSongId = songRepository.save(
                buildSong("잔잔한 곡", "C 아티스트", Mood.CALM)).getId();
    }

    @Test
    @DisplayName("#1485: 요청 분위기를 UPBEAT↔EMOTIONAL 로 바꾸면 1위 추천 곡이 뒤바뀐다 (mood 입력이 결과에 반영)")
    void differentRequestedMood_flipsTopRecommendation() {
        // given: 키/BPM 동일, preferredBpm 도 곡 BPM 과 같게 고정해 tempo 신호까지 동률 → mood 만 변별.
        final String upbeatPayload = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-1485deadce01",
                  "voiceRangeLow": 50,
                  "voiceRangeHigh": 70,
                  "mood": "UPBEAT",
                  "preferredBpm": 120
                }
                """;
        final String emotionalPayload = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-1485deadce02",
                  "voiceRangeLow": 50,
                  "voiceRangeHigh": 70,
                  "mood": "EMOTIONAL",
                  "preferredBpm": 120
                }
                """;
        // when
        final List<Integer> upbeatOrder = postAndExtractSongIds(upbeatPayload);
        final List<Integer> emotionalOrder = postAndExtractSongIds(emotionalPayload);
        // then: UPBEAT 요청이면 댄스 곡이, EMOTIONAL 요청이면 슬픈 발라드가 1위 — 순위가 실제로 뒤바뀐다.
        assertThat(upbeatOrder.get(0)).isEqualTo((int) upbeatSongId);
        assertThat(emotionalOrder.get(0)).isEqualTo((int) emotionalSongId);
        assertThat(upbeatOrder).isNotEqualTo(emotionalOrder);
    }

    @Test
    @DisplayName("#1485: EMOTIONAL 요청 시 가까운 분위기(NOSTALGIC) > 중간(CALM) > 먼 분위기(UPBEAT) 순으로 상위 (연속 변별력)")
    void relatedMood_rankedAboveDistantMood() {
        // given: 슬픈 발라드(EMOTIONAL) 요청. 정확 일치 곡을 제외해 "불일치끼리도 가까울수록 위"인지 검증.
        final String payload = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-1485deadce03",
                  "voiceRangeLow": 50,
                  "voiceRangeHigh": 70,
                  "mood": "EMOTIONAL",
                  "preferredBpm": 120,
                  "excludeSongIds": [%d]
                }
                """.formatted(emotionalSongId);
        // when
        final List<Integer> order = postAndExtractSongIds(payload);
        // then: NOSTALGIC(유사 0.90) > CALM(0.68) > UPBEAT(0.29) — 정확 일치가 빠져도 가까운 분위기가 상위.
        assertThat(order.indexOf((int) nostalgicSongId))
                .isLessThan(order.indexOf((int) calmSongId));
        assertThat(order.indexOf((int) calmSongId))
                .isLessThan(order.indexOf((int) upbeatSongId));
    }

    @Test
    @DisplayName("#1485: 추천 응답에 분위기 적합도(moodFit)와 한국어 사유(moodFitReason)가 전면 노출된다")
    void response_exposesMoodFitAndReason() {
        final String payload = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-1485deadce04",
                  "voiceRangeLow": 50,
                  "voiceRangeHigh": 70,
                  "mood": "EMOTIONAL",
                  "preferredBpm": 120
                }
                """;
        // 정확 일치 곡(슬픈 발라드)은 moodFit=1.0 + "딱 맞아요" 사유가 노출돼야 한다.
        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(payload)
                .when()
                .post("/api/v1/recommendations")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .body("recommendations.find { it.song.id == %d }.moodFit".formatted(emotionalSongId),
                        org.hamcrest.Matchers.equalTo(1.0f))
                .body("recommendations.find { it.song.id == %d }.moodFitReason".formatted(emotionalSongId),
                        org.hamcrest.Matchers.equalTo("요청하신 분위기와 딱 맞아요"));
    }

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

    private static Song buildSong(final String title, final String artist, final Mood mood) {
        return Song.builder()
                .title(title).artist(artist).releaseYear(2020)
                .keyOriginal(MusicalKey.C_MAJOR).bpm(120).mood(mood)
                .language("ko").genre("팝")
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
    }
}
