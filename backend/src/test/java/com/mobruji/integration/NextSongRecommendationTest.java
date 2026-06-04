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
import com.mobruji.recommendation.infrastructure.MusicalKeyMidiResolver;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

import io.restassured.RestAssured;

/**
 * "부른 곡 기반 다음곡 추천" {@code POST /api/v1/recommendations/next} E2E (#1486).
 *
 * <p>이슈 #1486 (roadmap {@code docs/roadmap/overnight-2026-06-02.md} P-B). 사용자가 부른 곡(seed)을
 * 입력하면 그 곡들의 음역대·분위기·BPM 을 도출해 이어 부르기 좋은 다음 곡을 추천한다. 쇼츠식 스와이프(#1489)의
 * 백엔드 토대.
 *
 * <p>검증 포인트:
 * <ul>
 * <li>성공 경로(201) + 결과 매핑.</li>
 * <li>부른 곡(seed)은 결과에 절대 포함되지 않음(자동 제외).</li>
 * <li>excludeSongIds 추가 제외분도 결과에서 빠짐.</li>
 * <li>같은 seed 집합은 결정적으로 같은 결과(결정성 보존).</li>
 * <li>조회되지 않는 seed ID 만 보내면 422.</li>
 * <li>seedSongIds 빈 배열은 400(검증).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class NextSongRecommendationTest {

    private static final String SESSION_ID = "550e8400-e29b-41d4-a716-11eeb0a55001";

    @LocalServerPort
    private int port;

    @Autowired
    private SongRepository songRepository;

    @Autowired
    private RecommendationRequestRepository recommendationRequestRepository;

    @Autowired
    private RecommendationRepository recommendationRepository;

    private Long iuLilacId;
    private Long iuEightId;
    private Long btsDynamiteId;
    private Long busker1Id;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        recommendationRepository.deleteAll();
        recommendationRequestRepository.deleteAll();
        songRepository.deleteAll();
        iuLilacId = songRepository.save(
                buildSong("Lilac", "IU", MusicalKey.D_MAJOR, Mood.UPBEAT, "팝")).getId();
        iuEightId = songRepository.save(
                buildSong("Eight", "IU", MusicalKey.A_MAJOR, Mood.NOSTALGIC, "팝")).getId();
        btsDynamiteId = songRepository.save(
                buildSong("Dynamite", "BTS", MusicalKey.E_MAJOR, Mood.UPBEAT, "댄스")).getId();
        busker1Id = songRepository.save(
                buildSong("벚꽃 엔딩", "버스커 버스커", MusicalKey.A_MAJOR, Mood.EMOTIONAL, "발라드")).getId();
        songRepository.save(buildSong("좋은 날", "IU", MusicalKey.C_MAJOR, Mood.UPBEAT, "팝"));
        songRepository.save(buildSong("Spring Day", "BTS", MusicalKey.D_SHARP_MAJOR, Mood.EMOTIONAL, "발라드"));
        songRepository.save(buildSong("취중진담", "전람회", MusicalKey.F_MAJOR, Mood.EMOTIONAL, "발라드"));
    }

    @Test
    @DisplayName("부른 곡 기반 다음곡 추천 — 201 + 결과가 비어있지 않다")
    void next_validSeeds_returns201WithResults() {
        // given: IU Lilac 를 부른 뒤 다음 곡 추천
        final String payload = """
                {
                  "sessionId": "%s",
                  "seedSongIds": [%d]
                }
                """.formatted(SESSION_ID, iuLilacId);

        // when
        final List<Integer> songIds = postNextAndExtractSongIds(payload);

        // then
        assertThat(songIds).isNotEmpty();
    }

    @Test
    @DisplayName("부른 곡(seed)은 결과에 절대 포함되지 않는다 (자동 제외)")
    void next_excludesSeedSongs() {
        // given
        final String payload = """
                {
                  "sessionId": "%s",
                  "seedSongIds": [%d, %d]
                }
                """.formatted(SESSION_ID, iuLilacId, btsDynamiteId);

        // when
        final List<Integer> songIds = postNextAndExtractSongIds(payload);

        // then
        assertThat(songIds).doesNotContain(iuLilacId.intValue(), btsDynamiteId.intValue());
    }

    @Test
    @DisplayName("excludeSongIds 추가 제외분도 결과에서 빠진다")
    void next_appliesExtraExcludes() {
        // given: IU Lilac 부름 + busker 곡은 명시 제외(스와이프 패스)
        final String payload = """
                {
                  "sessionId": "%s",
                  "seedSongIds": [%d],
                  "excludeSongIds": [%d]
                }
                """.formatted(SESSION_ID, iuLilacId, busker1Id);

        // when
        final List<Integer> songIds = postNextAndExtractSongIds(payload);

        // then
        assertThat(songIds).doesNotContain(iuLilacId.intValue(), busker1Id.intValue());
    }

    @Test
    @DisplayName("같은 seed 집합은 결정적으로 같은 결과를 반환한다")
    void next_deterministicForSameSeeds() {
        // given
        final String payload = """
                {
                  "sessionId": "%s",
                  "seedSongIds": [%d, %d]
                }
                """.formatted(SESSION_ID, iuLilacId, iuEightId);

        // when
        final List<Integer> first = postNextAndExtractSongIds(payload);
        final List<Integer> second = postNextAndExtractSongIds(payload);

        // then
        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("조회되지 않는 seed ID 만 보내면 422")
    void next_unknownSeeds_returns422() {
        final String payload = """
                {
                  "sessionId": "%s",
                  "seedSongIds": [999999, 888888]
                }
                """.formatted(SESSION_ID);

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(payload)
                .when()
                .post("/api/v1/recommendations/next")
                .then()
                .statusCode(HttpStatus.UNPROCESSABLE_ENTITY.value());
    }

    @Test
    @DisplayName("seedSongIds 빈 배열은 400 (검증)")
    void next_emptySeeds_returns400() {
        final String payload = """
                {
                  "sessionId": "%s",
                  "seedSongIds": []
                }
                """.formatted(SESSION_ID);

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(payload)
                .when()
                .post("/api/v1/recommendations/next")
                .then()
                .statusCode(HttpStatus.BAD_REQUEST.value());
    }

    private List<Integer> postNextAndExtractSongIds(final String payload) {
        return given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(payload)
                .when()
                .post("/api/v1/recommendations/next")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .extract().jsonPath().getList("recommendations.song.id", Integer.class);
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
