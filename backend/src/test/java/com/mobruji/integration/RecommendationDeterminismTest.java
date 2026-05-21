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

import com.mobruji.recommendation.RecommendationRepository;
import com.mobruji.recommendation.RecommendationRequestRepository;
import com.mobruji.song.MetadataSource;
import com.mobruji.song.Mood;
import com.mobruji.song.MusicalKey;
import com.mobruji.song.Song;
import com.mobruji.song.SongRepository;

import io.restassured.RestAssured;

/**
 * 추천 결정성 회귀 테스트.
 *
 * <p>spec: {@code docs/features/recommendation-algorithm-v1.md} §3 비기능 — 결정성
 * (같은 입력 → 같은 결과). 동일 페이로드 두 번 호출 시 곡 ID 순서가 일치하는지,
 * 입력이 달라지면 entropy가 살아 있는지를 RestAssured E2E로 검증한다.
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
                  "sessionId": "determinism-stable",
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
    @DisplayName("entropy 보존: 다른 sessionId → 적어도 한 자리에서 순서가 달라진다")
    void determinism_differentInput_yieldsDifferentOrder() {
        // given: sessionId만 다르고 나머지는 동일 → jitter seed가 달라져야 함
        final String payloadA = """
                {
                  "sessionId": "entropy-a",
                  "voiceRangeLow": 55,
                  "voiceRangeHigh": 75,
                  "mood": "UPBEAT"
                }
                """;
        final String payloadB = """
                {
                  "sessionId": "entropy-b",
                  "voiceRangeLow": 55,
                  "voiceRangeHigh": 75,
                  "mood": "UPBEAT"
                }
                """;
        // when
        final List<Integer> orderA = postAndExtractSongIds(payloadA);
        final List<Integer> orderB = postAndExtractSongIds(payloadB);
        // then: 동순위 후보가 많은 시드에서 jitter가 의미 있게 흔드는지 확인.
        // 곡 집합은 같지만(다양성 캡 통과 후보 풀이 작아 동일할 수 있음) 적어도 순서가 달라야 한다.
        assertThat(orderA).isNotEqualTo(orderB);
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
