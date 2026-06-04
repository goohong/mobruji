package com.mobruji.integration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;

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

import com.mobruji.recommendation.infrastructure.MusicalKeyMidiResolver;
import com.mobruji.recommendation.infrastructure.RecommendationRepository;
import com.mobruji.recommendation.infrastructure.RecommendationRequestRepository;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

import io.restassured.RestAssured;

/**
 * P-D 모임 사회자형 시퀀스 추천(persona-expansion-social-emotional.md §2/§5) E2E.
 *
 * <p>{@code POST /api/v1/recommendations/sequence} 가 워밍업/고조/마무리 3단계 묶음을 단계별 다른 분위기로 산출하고,
 * 단계 간 곡이 겹치지 않는지(누적 제외), fe(#1601) 연동 응답 계약(persona + 단계별 requestId + 단일 추천과 같은 곡 형상)을
 * 검증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatheringSequenceRecommendationTest {

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
        // 단계별로 다른 분위기를 변별할 수 있도록 분위기를 섞어 6곡 시드. 단계 간 중복 제외(누적)를 검증하려면 단계 수 이상이어야 한다.
        songRepository.save(buildSong("잔잔한곡", "가수A", MusicalKey.C_MAJOR, Mood.CALM, "발라드"));
        songRepository.save(buildSong("신나는곡", "가수B", MusicalKey.D_MAJOR, Mood.UPBEAT, "댄스"));
        songRepository.save(buildSong("감성곡", "가수C", MusicalKey.E_MAJOR, Mood.EMOTIONAL, "발라드"));
        songRepository.save(buildSong("그루브곡", "가수D", MusicalKey.F_MAJOR, Mood.GROOVY, "팝"));
        songRepository.save(buildSong("파워곡", "가수E", MusicalKey.G_MAJOR, Mood.POWERFUL, "록"));
        songRepository.save(buildSong("추억곡", "가수F", MusicalKey.A_MAJOR, Mood.NOSTALGIC, "팝"));
    }

    @Test
    @DisplayName("E2E (#1599): 시퀀스 추천 → 201 + persona=P-D + 워밍업/고조/마무리 3단계 + 단계별 곡·requestId 노출")
    void e2e_sequenceReturnsThreeStages() {
        final String createBody = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-11eeec0e2d01",
                  "voiceRangeLow": 55,
                  "voiceRangeHigh": 75,
                  "ageGroup": "TWENTIES"
                }
                """;

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(createBody)
                .when()
                .post("/api/v1/recommendations/sequence")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .body("persona", equalTo("P-D"))
                .body("stages.size()", equalTo(3))
                // 자리 흐름 순서 고정: 워밍업 → 고조 → 마무리.
                .body("stages.stage", contains("WARMUP", "PEAK", "CLOSING"))
                .body("stages.mood", contains("CALM", "UPBEAT", "EMOTIONAL"))
                .body("stages.stageReason", everyItem(notNullValue()))
                // 각 단계는 고유 추천 요청으로 영속 → requestId 노출(단계별 피드백·재조회 경로 재사용).
                .body("stages.requestId", everyItem(notNullValue()))
                // 모든 단계가 곡으로 채워진다(사회자는 빈 단계를 원치 않음 — 0건 fallback).
                .body("stages[0].recommendations.size()", greaterThan(0))
                .body("stages[1].recommendations.size()", greaterThan(0))
                .body("stages[2].recommendations.size()", greaterThan(0))
                // 단계 안 곡은 단일 추천과 같은 형상(설명 가능성 필드 재사용).
                .body("stages[0].recommendations[0].song.title", notNullValue())
                .body("stages[0].recommendations[0].rankPosition", equalTo(1))
                .body("stages[0].recommendations[0].voiceFit", notNullValue());
    }

    @Test
    @DisplayName("E2E (#1599): songsPerStage=1 이면 단계별 1곡 + 단계 간 곡 중복 없음(누적 제외)")
    void e2e_songsPerStageCapAndNoCrossStageDuplicate() {
        final String createBody = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-11eeec0e2d02",
                  "voiceRangeLow": 55,
                  "voiceRangeHigh": 75,
                  "songsPerStage": 1
                }
                """;

        final List<Integer> songIds = given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(createBody)
                .when()
                .post("/api/v1/recommendations/sequence")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .body("stages.size()", equalTo(3))
                // 단계별 노출 곡 수 상한 적용.
                .body("stages[0].recommendations.size()", equalTo(1))
                .body("stages[1].recommendations.size()", equalTo(1))
                .body("stages[2].recommendations.size()", equalTo(1))
                .extract().path("stages.recommendations[0].song.id");

        // 풀이 충분(6곡)하므로 세 단계의 대표 곡은 서로 달라야 한다(누적 제외로 재노출 금지).
        org.junit.jupiter.api.Assertions.assertEquals(
                songIds.size(), songIds.stream().distinct().count(),
                "단계 간 곡이 중복되면 안 된다: " + songIds);
    }

    @Test
    @DisplayName("E2E (#1599): sessionId 가 UUIDv4 가 아니면 400 (단일 추천과 같은 검증 일관성)")
    void e2e_validationFailure() {
        final String invalidBody = """
                {
                  "sessionId": "not-a-uuid",
                  "voiceRangeLow": 55,
                  "voiceRangeHigh": 75
                }
                """;

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(invalidBody)
                .when()
                .post("/api/v1/recommendations/sequence")
                .then()
                .statusCode(HttpStatus.BAD_REQUEST.value())
                .body("fieldErrors.find { it.field == 'sessionId' }", notNullValue());
    }

    private static Song buildSong(
            final String title, final String artist, final MusicalKey key, final Mood mood, final String genre) {
        return Song.builder()
                .title(title).artist(artist).releaseYear(2020)
                .keyOriginal(key).bpm(120).mood(mood)
                .lowMidi(MusicalKeyMidiResolver.rootMidi(key) - 7)
                .highMidi(MusicalKeyMidiResolver.rootMidi(key) + 7)
                .language("ko").genre(genre)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
    }
}
