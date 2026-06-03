package com.mobruji.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
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
 * 세션 단위 자동 중복 회피({@code excludeSessionHistory}) 동작 검증 (#1549).
 *
 * <p>spec: {@code docs/features/recommendation-algorithm-v1.md} §3 기능 요구사항 — "이미 들었어요" 제외를
 * 세션 히스토리로 자동 누적해 반복 추천을 막는다.
 *
 * <p>검증 포인트:
 * <ul>
 * <li>{@code excludeSessionHistory=true} 면 같은 세션의 두 번째 추천이 첫 결과 곡을 하나도 포함하지 않는다.</li>
 * <li>세 번째까지 누적돼 점점 새 곡만 노출된다.</li>
 * <li>플래그 미입력(기본 false)은 기존 동작 — 같은 입력 반복 시 같은 결과(결정성).</li>
 * <li>{@code /next}(부른 곡 기반)에도 플래그가 전파돼 이전 추천 곡 + seed 가 누적 제외된다.</li>
 * </ul>
 *
 * <p>{@code result-count=10}(application.yml) 이므로 한 회 추천에 최대 10곡. 두 회·세 회 누적 제외 후에도
 * 결과가 비지 않도록 24곡(유니크 아티스트 + 다양한 장르)을 시드해 다양성 캡(아티스트≤2/장르≤4) 영향을 배제한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RecommendationSessionHistoryDedupTest {

    private static final List<String> GENRES = List.of("발라드", "댄스", "팝", "록", "힙합", "재즈");

    @LocalServerPort
    private int port;

    @Autowired
    private SongRepository songRepository;

    @Autowired
    private RecommendationRequestRepository recommendationRequestRepository;

    @Autowired
    private RecommendationRepository recommendationRepository;

    private final List<Long> seededSongIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        recommendationRepository.deleteAll();
        recommendationRequestRepository.deleteAll();
        songRepository.deleteAll();
        seededSongIds.clear();
        // 24곡 — 유니크 아티스트 + 6개 장르 순환. 누적 제외 2~3회 후에도 후보가 남도록 충분한 풀.
        for (int i = 0; i < 24; i++) {
            final Long id = songRepository.save(buildSong(i)).getId();
            seededSongIds.add(id);
        }
    }

    @Test
    @DisplayName("excludeSessionHistory=true: 두 번째 추천은 첫 결과 곡을 하나도 포함하지 않는다")
    void sessionHistory_secondCallExcludesFirstResults() {
        final String sessionId = "550e8400-e29b-41d4-a716-4466554a0001";

        final List<Integer> first = postWithSessionHistory(sessionId);
        final List<Integer> second = postWithSessionHistory(sessionId);

        assertThat(first).isNotEmpty();
        assertThat(second).isNotEmpty();
        assertThat(second).doesNotContainAnyElementsOf(first);
    }

    @Test
    @DisplayName("excludeSessionHistory=true: 세 번째까지 누적 제외돼 이전 두 회 곡이 모두 빠진다")
    void sessionHistory_accumulatesAcrossThreeCalls() {
        final String sessionId = "550e8400-e29b-41d4-a716-4466554a0002";

        final List<Integer> first = postWithSessionHistory(sessionId);
        final List<Integer> second = postWithSessionHistory(sessionId);
        final List<Integer> third = postWithSessionHistory(sessionId);

        final List<Integer> priorTwo = new ArrayList<>(first);
        priorTwo.addAll(second);
        assertThat(third).isNotEmpty();
        assertThat(third).doesNotContainAnyElementsOf(priorTwo);
    }

    @Test
    @DisplayName("플래그 미입력(기본 false): 같은 입력 반복 시 같은 결과 — 기존 결정성 동작 보존")
    void noFlag_preservesDeterministicRepeat() {
        final String sessionId = "550e8400-e29b-41d4-a716-4466554a0003";
        final String payload = """
                {
                  "sessionId": "%s",
                  "voiceRangeLow": 50,
                  "voiceRangeHigh": 80,
                  "mood": "UPBEAT"
                }
                """.formatted(sessionId);

        final List<Integer> first = postAndExtractSongIds(payload);
        final List<Integer> second = postAndExtractSongIds(payload);

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("/next: excludeSessionHistory=true 면 이전 추천 곡 + seed 가 누적 제외된다")
    void next_propagatesSessionHistoryExclusion() {
        final String sessionId = "550e8400-e29b-41d4-a716-4466554a0004";
        final Long seedSongId = seededSongIds.get(0);

        final List<Integer> first = postNextWithSessionHistory(sessionId, seedSongId);
        final List<Integer> second = postNextWithSessionHistory(sessionId, seedSongId);

        assertThat(first).isNotEmpty();
        assertThat(second).isNotEmpty();
        // seed 는 두 회 모두 결과에서 제외(이미 부른 곡).
        assertThat(first).doesNotContain(seedSongId.intValue());
        assertThat(second).doesNotContain(seedSongId.intValue());
        // 두 번째는 첫 추천 곡을 누적 제외.
        assertThat(second).doesNotContainAnyElementsOf(first);
    }

    private List<Integer> postWithSessionHistory(final String sessionId) {
        final String payload = """
                {
                  "sessionId": "%s",
                  "voiceRangeLow": 50,
                  "voiceRangeHigh": 80,
                  "mood": "UPBEAT",
                  "excludeSessionHistory": true
                }
                """.formatted(sessionId);
        return postAndExtractSongIds(payload);
    }

    private List<Integer> postNextWithSessionHistory(final String sessionId, final Long seedSongId) {
        final String payload = """
                {
                  "sessionId": "%s",
                  "seedSongIds": [%d],
                  "excludeSessionHistory": true
                }
                """.formatted(sessionId, seedSongId);
        return given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(payload)
                .when()
                .post("/api/v1/recommendations/next")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .extract().jsonPath().getList("recommendations.song.id", Integer.class);
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

    private static Song buildSong(final int index) {
        final MusicalKey[] keys = MusicalKey.values();
        return Song.builder()
                .title("곡-" + index)
                .artist("아티스트-" + index)
                .releaseYear(2000 + (index % 24))
                .keyOriginal(keys[index % keys.length])
                .bpm(120)
                .mood(Mood.UPBEAT)
                .language("ko")
                .genre(GENRES.get(index % GENRES.size()))
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build();
    }
}
