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
 * 음역대 입력 변별력 회귀 테스트 (#1452).
 *
 * <p>버그: {@code voiceRangeFit} 가 overlap/songSpan 으로만 산정돼, 사용자 음역대가 곡 음역(root±7)을
 * 완전히 포함하는 일반적 경우 모든 곡이 1.0 으로 포화 → 음역대를 바꿔도 추천 순위가 동일했다.
 *
 * <p>fix: reachability × centeredness 로 산정해 곡 키 중심이 사용자 음역 중앙에 가까울수록 높게 평가.
 * 본 테스트는 mood/BPM 이 동일하고 키만 다른 두 곡으로, 음역대 중심을 낮게/높게 바꾸면 1위 곡이
 * 뒤바뀌는지를 RestAssured E2E 로 검증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RecommendationVoiceRangeSensitivityTest {

    @LocalServerPort
    private int port;

    @Autowired
    private SongRepository songRepository;

    @Autowired
    private RecommendationRequestRepository recommendationRequestRepository;

    @Autowired
    private RecommendationRepository recommendationRepository;

    private long lowKeySongId;
    private long highKeySongId;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        recommendationRepository.deleteAll();
        recommendationRequestRepository.deleteAll();
        songRepository.deleteAll();
        // mood/BPM 동일, 키만 다른 두 곡 — 변별 신호를 voiceFit 로 격리.
        lowKeySongId = songRepository.save(
                buildSong("저음 곡", "C 아티스트", MusicalKey.C_MAJOR, Mood.UPBEAT)).getId();
        highKeySongId = songRepository.save(
                buildSong("고음 곡", "B 아티스트", MusicalKey.B_MAJOR, Mood.UPBEAT)).getId();
    }

    @Test
    @DisplayName("#1452: 음역대 중심을 낮게/높게 바꾸면 1위 추천 곡이 뒤바뀐다 (음역대 입력이 결과에 반영)")
    void differentVoiceRangeCenter_flipsTopRecommendation() {
        // given: 저음 중심(center=60, C major root) vs 고음 중심(center=71, B major root)
        final String lowCenterPayload = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-1452deadce01",
                  "voiceRangeLow": 50,
                  "voiceRangeHigh": 70,
                  "mood": "UPBEAT"
                }
                """;
        final String highCenterPayload = """
                {
                  "sessionId": "550e8400-e29b-41d4-a716-1452deadce02",
                  "voiceRangeLow": 61,
                  "voiceRangeHigh": 81,
                  "mood": "UPBEAT"
                }
                """;
        // when
        final List<Integer> lowOrder = postAndExtractSongIds(lowCenterPayload);
        final List<Integer> highOrder = postAndExtractSongIds(highCenterPayload);
        // then: 저음 중심이면 C major(저음 곡)가, 고음 중심이면 B major(고음 곡)가 1위 — 순위가 실제로 달라진다.
        assertThat(lowOrder.get(0)).isEqualTo((int) lowKeySongId);
        assertThat(highOrder.get(0)).isEqualTo((int) highKeySongId);
        assertThat(lowOrder).isNotEqualTo(highOrder);
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
            final String title, final String artist, final MusicalKey key, final Mood mood) {
        return Song.builder()
                .title(title).artist(artist).releaseYear(2020)
                .keyOriginal(key).bpm(120).mood(mood)
                .language("ko").genre("팝")
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
    }
}
