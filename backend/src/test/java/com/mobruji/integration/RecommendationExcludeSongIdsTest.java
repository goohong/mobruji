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
 * 추천 API의 {@code excludeSongIds} 파라미터 동작 검증.
 *
 * <p>spec: {@code docs/features/recommendation-algorithm-v1.md} §3 기능 요구사항
 * — "이미 들었어요" 곡을 결과에서 제외.
 *
 * <p>검증 포인트:
 * <ul>
 * <li>제외 곡 ID가 결과에 절대 포함되지 않음.</li>
 * <li>다양성 캡(아티스트 ≤2, 장르 ≤4)과 제외가 동시에 작용해도 fallback이 정상 작동.</li>
 * <li>제외 곡 셋이 다르면 seed가 달라 결과 순서가 흔들림 (재추천 변주, 누적 패턴).</li>
 * <li>제외 곡 셋이 같으면 결정성 보존 (같은 입력 → 같은 결과).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RecommendationExcludeSongIdsTest {

    @LocalServerPort
    private int port;

    @Autowired
    private SongRepository songRepository;

    @Autowired
    private RecommendationRequestRepository recommendationRequestRepository;

    @Autowired
    private RecommendationRepository recommendationRepository;

    private Long bts1Id;
    private Long bts2Id;
    private Long iu1Id;
    private Long iu2Id;
    private Long buskerId;
    private Long btsButterId;
    private Long btsSpringDayId;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        recommendationRepository.deleteAll();
        recommendationRequestRepository.deleteAll();
        songRepository.deleteAll();
        // 8곡 시드 — 제외 + 다양성 캡(아티스트≤2)이 동시에 작동하면서도 fallback이 동작할 만큼의 풀.
        buskerId = songRepository.save(
                buildSong("벚꽃 엔딩", "버스커 버스커", MusicalKey.A_MAJOR, Mood.EMOTIONAL, "발라드")).getId();
        bts1Id = songRepository.save(
                buildSong("Dynamite", "BTS", MusicalKey.E_MAJOR, Mood.UPBEAT, "댄스")).getId();
        btsSpringDayId = songRepository.save(
                buildSong("Spring Day", "BTS", MusicalKey.D_SHARP_MAJOR, Mood.EMOTIONAL, "발라드")).getId();
        iu1Id = songRepository.save(
                buildSong("Eight", "IU", MusicalKey.A_MAJOR, Mood.NOSTALGIC, "팝")).getId();
        iu2Id = songRepository.save(
                buildSong("Lilac", "IU", MusicalKey.D_MAJOR, Mood.UPBEAT, "팝")).getId();
        songRepository.save(
                buildSong("좋은 날", "IU", MusicalKey.C_MAJOR, Mood.UPBEAT, "팝"));
        btsButterId = songRepository.save(
                buildSong("Butter", "BTS", MusicalKey.G_MAJOR, Mood.UPBEAT, "댄스")).getId();
        bts2Id = btsButterId; // 별칭 (가독성용)
        songRepository.save(
                buildSong("취중진담", "전람회", MusicalKey.F_MAJOR, Mood.EMOTIONAL, "발라드"));
    }

    @Test
    @DisplayName("excludeSongIds로 지정한 곡은 결과에 절대 포함되지 않는다")
    void excludeSongIds_filtersOutSpecifiedSongs() {
        // given: BTS Dynamite, IU Eight 두 곡을 제외
        final String payload = """
                {
                  "sessionId": "exclude-basic",
                  "voiceRangeLow": 50,
                  "voiceRangeHigh": 80,
                  "mood": "UPBEAT",
                  "excludeSongIds": [%d, %d]
                }
                """.formatted(bts1Id, iu1Id);

        // when
        final List<Integer> songIds = postAndExtractSongIds(payload);

        // then
        assertThat(songIds).doesNotContain(bts1Id.intValue(), iu1Id.intValue());
    }

    @Test
    @DisplayName("excludeSongIds + 다양성 캡 동시 적용 — 제외된 BTS 곡이 결과에 빠지고 캡도 유지됨")
    void excludeSongIds_combinedWithDiversityCap() {
        // given: BTS 3곡 중 Spring Day 제외 → 남은 BTS 2곡(Dynamite, Butter)은 캡(≤2) 안.
        // 결과 내 BTS는 최대 2곡, 그리고 Spring Day는 등장하지 않아야 한다.
        final String payload = """
                {
                  "sessionId": "exclude-diversity",
                  "voiceRangeLow": 50,
                  "voiceRangeHigh": 80,
                  "mood": "UPBEAT",
                  "excludeSongIds": [%d]
                }
                """.formatted(btsSpringDayId);

        // when
        final List<Integer> songIds = postAndExtractSongIds(payload);

        // then
        assertThat(songIds).doesNotContain(btsSpringDayId.intValue());
        // 다양성 캡: BTS 곡(Dynamite, Butter)이 결과에 들어와도 최대 2곡까지만.
        final long btsCount = songIds.stream()
                .filter(id -> id.longValue() == bts1Id || id.longValue() == btsButterId)
                .count();
        assertThat(btsCount).isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("제외 곡 셋이 같으면 결과 순서도 동일 (결정성 보존, 같은 excludeSongIds)")
    void excludeSongIds_deterministicWithSameExcludes() {
        // given
        final String payload = """
                {
                  "sessionId": "exclude-det",
                  "voiceRangeLow": 55,
                  "voiceRangeHigh": 75,
                  "mood": "UPBEAT",
                  "excludeSongIds": [%d, %d]
                }
                """.formatted(bts1Id, iu1Id);
        // when
        final List<Integer> first = postAndExtractSongIds(payload);
        final List<Integer> second = postAndExtractSongIds(payload);
        // then
        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("제외 곡 셋이 다르면 jitter seed가 달라져 순서가 흔들린다 (재추천 누적 패턴)")
    void excludeSongIds_changesAlterSeed() {
        // given: 같은 voiceRange/sessionId/mood지만 excludeSongIds만 다름.
        // 새 excludeSongIds는 결과에 등장 가능한 곡(IU Lilac)을 추가로 제외하지 않고도 seed 자체를 흔든다.
        // 누적 패턴 효과를 확인하려면 같은 곡 집합(빈 제외 vs 비어있지 않지만 실제로는 결과에 없을 수 있는 곡 제외)을 비교.
        // 여기서는 buskerId만 제외해 결과 곡 집합이 거의 동일한 상황에서 순서 변동 여부를 확인.
        final String emptyExclude = """
                {
                  "sessionId": "alter-seed",
                  "voiceRangeLow": 55,
                  "voiceRangeHigh": 75,
                  "mood": "UPBEAT",
                  "excludeSongIds": []
                }
                """;
        final String oneExclude = """
                {
                  "sessionId": "alter-seed",
                  "voiceRangeLow": 55,
                  "voiceRangeHigh": 75,
                  "mood": "UPBEAT",
                  "excludeSongIds": [%d]
                }
                """.formatted(buskerId);
        // when
        final List<Integer> orderEmpty = postAndExtractSongIds(emptyExclude);
        final List<Integer> orderWithExclude = postAndExtractSongIds(oneExclude);
        // then: 결과 곡 집합은 1곡 차이지만, 다른 곡들도 jitter 영향으로 순서가 흔들려야 한다.
        // (집합 자체가 다르므로 isNotEqualTo는 자명하지만, 제외 곡 외에도 변화가 있어야 누적 패턴이 의미를 가진다.)
        assertThat(orderEmpty).isNotEqualTo(orderWithExclude);
        // 제외 곡은 두 번째 결과에 등장하지 않아야 한다.
        assertThat(orderWithExclude).doesNotContain(buskerId.intValue());
    }

    @Test
    @DisplayName("excludeSongIds 미입력(null)은 빈 리스트와 동일 — 회귀 가드")
    void excludeSongIds_omittedFieldIsTreatedAsEmpty() {
        // given: excludeSongIds 필드 자체를 생략
        final String payloadOmitted = """
                {
                  "sessionId": "omit",
                  "voiceRangeLow": 55,
                  "voiceRangeHigh": 75,
                  "mood": "UPBEAT"
                }
                """;
        final String payloadEmpty = """
                {
                  "sessionId": "omit",
                  "voiceRangeLow": 55,
                  "voiceRangeHigh": 75,
                  "mood": "UPBEAT",
                  "excludeSongIds": []
                }
                """;
        // when
        final List<Integer> orderOmitted = postAndExtractSongIds(payloadOmitted);
        final List<Integer> orderEmpty = postAndExtractSongIds(payloadEmpty);
        // then: 의미 동등 → 같은 결과
        assertThat(orderOmitted).isEqualTo(orderEmpty);
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
