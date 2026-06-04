package com.mobruji.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

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
 * 추천 결과 0건 처리 E2E — 빈 exclude fallback(#1668) ↔ 페이지네이션 풀 소진 종료(#1835) 분기.
 *
 * <p>클라이언트가 제외 곡을 명시하지 않은 빈 exclude(=재추천 버튼)면 정상 후보가 0건이어도 제외 필터를 완화해 최소 결과를
 * 반환한다(#1668). 클라이언트가 제외 곡을 누적해 보내는 페이지네이션 맥락(#1835)이면 풀 소진 시 fallback 없이 빈 결과로
 * 종료해 이미 본 곡을 재노출(재surface)하지 않는다. 정상 매칭은 {@code relaxed=false} 회귀가 없어야 한다.
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
    @DisplayName("클라이언트가 카탈로그 전 곡을 제외(페이지네이션 풀 소진)하면 fallback 없이 빈 결과로 종료한다 (#1835)")
    void clientExcludesPoolExhausted_terminatesEmpty() {
        // given: 카탈로그 3곡을 전부 명시 제외 → 정상 후보 0건 (무한스크롤로 전부 본 상황)
        final String payload = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-aaaa00001835",
                  "voiceRangeLow": 50,
                  "voiceRangeHigh": 80,
                  "mood": "UPBEAT",
                  "excludeSongIds": [%d, %d, %d]
                }
                """.formatted(song1Id, song2Id, song3Id);

        // when
        final Response response = post(payload);

        // then: 201 + 빈 결과로 종료(재surface 금지) + relaxed=false
        response.then().statusCode(HttpStatus.CREATED.value());
        assertThat(response.jsonPath().getList("recommendations.song.id", Integer.class)).isEmpty();
        assertThat(response.jsonPath().getBoolean("relaxed")).isFalse();
        assertThat(response.jsonPath().getList("relaxedFilters", String.class)).isEmpty();
    }

    @Test
    @DisplayName("페이지네이션: 빈 exclude 첫 호출은 결과를 주고, 본 곡을 누적 제외하면 풀 소진 시 n=0 으로 종료한다 (#1835)")
    void pagination_accumulatesExcludesUntilPoolExhausted() {
        final String sessionId = "550e8400-e29b-41d4-a716-aaaa00001836";

        // given/when 1: 빈 exclude(재추천 버튼 첫 호출) → 결과를 받는다
        final Response firstPage = post("""
                {
                  "sessionId": "%s",
                  "voiceRangeLow": 50,
                  "voiceRangeHigh": 80,
                  "mood": "UPBEAT",
                  "excludeSongIds": []
                }
                """.formatted(sessionId));
        firstPage.then().statusCode(HttpStatus.CREATED.value());
        assertThat(firstPage.jsonPath().getList("recommendations.song.id", Integer.class)).isNotEmpty();

        // when 2: 본 곡(=카탈로그 전 곡)을 excludeSongIds 에 누적 → 풀 소진
        final Response exhausted = post("""
                {
                  "sessionId": "%s",
                  "voiceRangeLow": 50,
                  "voiceRangeHigh": 80,
                  "mood": "UPBEAT",
                  "excludeSongIds": [%d, %d, %d]
                }
                """.formatted(sessionId, song1Id, song2Id, song3Id));

        // then: 빈 결과로 종료 — 이미 본 곡을 다시 노출하지 않는다
        exhausted.then().statusCode(HttpStatus.CREATED.value());
        assertThat(exhausted.jsonPath().getList("recommendations.song.id", Integer.class)).isEmpty();
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
