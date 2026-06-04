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
import io.restassured.response.Response;

/**
 * 추천 결과 0건(빈 화면) fallback(#1668) E2E.
 *
 * <p>spec: 이슈 #1668 — 좁은 필터(여기서는 카탈로그 전 곡 제외)로 정상 후보가 0건이어도, 제외 필터를 완화해 최소
 * 결과를 점수 순(가까운 순)으로 반환하고 {@code relaxed=true} + 완화된 필터를 표기한다. 정상 매칭은
 * {@code relaxed=false} 회귀가 없어야 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RecommendationZeroResultFallbackTest {

    @LocalServerPort
    private int port;

    @Autowired
    private SongRepository songRepository;

    @Autowired
    private RecommendationRequestRepository recommendationRequestRepository;

    @Autowired
    private RecommendationRepository recommendationRepository;

    private Long song1Id;
    private Long song2Id;
    private Long song3Id;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        recommendationRepository.deleteAll();
        recommendationRequestRepository.deleteAll();
        songRepository.deleteAll();
        song1Id = songRepository.save(
                buildSong("Dynamite", "BTS", MusicalKey.E_MAJOR, Mood.UPBEAT, "댄스")).getId();
        song2Id = songRepository.save(
                buildSong("Lilac", "IU", MusicalKey.D_MAJOR, Mood.UPBEAT, "팝")).getId();
        song3Id = songRepository.save(
                buildSong("벚꽃 엔딩", "버스커 버스커", MusicalKey.A_MAJOR, Mood.EMOTIONAL, "발라드")).getId();
    }

    @Test
    @DisplayName("카탈로그 전 곡을 제외해 정상 후보가 0건이어도, 완화 fallback 으로 결과가 채워진다")
    void allExcluded_relaxesAndReturnsNonEmpty() {
        // given: 카탈로그 3곡을 전부 제외 → 정상 후보 0건
        final String payload = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-aaaa00001668",
                  "voiceRangeLow": 50,
                  "voiceRangeHigh": 80,
                  "mood": "UPBEAT",
                  "excludeSongIds": [%d, %d, %d]
                }
                """.formatted(song1Id, song2Id, song3Id);

        // when
        final Response response = post(payload);

        // then: 201 + 비어 있지 않은 결과 + relaxed 표기
        response.then().statusCode(HttpStatus.CREATED.value());
        final List<Integer> songIds = response.jsonPath().getList("recommendations.song.id", Integer.class);
        assertThat(songIds).isNotEmpty();
        assertThat(response.jsonPath().getBoolean("relaxed")).isTrue();
        assertThat(response.jsonPath().getList("relaxedFilters", String.class)).contains("EXCLUDED_SONGS");
    }

    @Test
    @DisplayName("정상 매칭(제외 없음)은 relaxed=false + relaxedFilters 가 비어 있다 — 회귀 가드")
    void normalMatch_isNotRelaxed() {
        // given: 제외 없음 → 정상 후보 존재
        final String payload = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-aaaa00001669",
                  "voiceRangeLow": 50,
                  "voiceRangeHigh": 80,
                  "mood": "UPBEAT",
                  "excludeSongIds": []
                }
                """;

        // when
        final Response response = post(payload);

        // then
        response.then().statusCode(HttpStatus.CREATED.value());
        assertThat(response.jsonPath().getList("recommendations.song.id", Integer.class)).isNotEmpty();
        assertThat(response.jsonPath().getBoolean("relaxed")).isFalse();
        assertThat(response.jsonPath().getList("relaxedFilters", String.class)).isEmpty();
    }

    private Response post(final String payload) {
        return given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(payload)
                .when()
                .post("/api/v1/recommendations");
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
