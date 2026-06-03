package com.mobruji.integration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

import io.restassured.RestAssured;

/**
 * 곡 검색/필터 진화(`GET /api/v1/songs` wrapper) + 자동완성(`/suggest`) E2E (spec
 * {@code song-search-and-filter.md} §7). 신규 endpoint 성공 E2E + 정책(빈 keyword / both-or-neither
 * fit / size 가드) 검증.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SongSearchE2ETest {

    @LocalServerPort
    private int port;

    @Autowired
    private SongRepository songRepository;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        songRepository.deleteAll();
        // 벚꽃 엔딩 — HARD(high 76), EMOTIONAL, 발라드, 음역 57~76. 초성 "ㅂㄲ ㅇㄷ".
        songRepository.save(Song.builder()
                .title("벚꽃 엔딩").artist("버스커 버스커").releaseYear(2012)
                .keyOriginal(MusicalKey.A_MAJOR).bpm(132).mood(Mood.EMOTIONAL)
                .language("ko").genre("발라드")
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(57).highMidi(76)
                .build());
        // Dynamite — HARD(high 78), UPBEAT, 댄스, 가수 BTS, 음역 59~78.
        songRepository.save(Song.builder()
                .title("Dynamite").artist("BTS").releaseYear(2020)
                .keyOriginal(MusicalKey.E_MAJOR).bpm(114).mood(Mood.UPBEAT)
                .language("en").genre("댄스")
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(59).highMidi(78)
                .build());
        // 잔잔한 발라드 — EASY(high 60), CALM, 발라드, 음역 50~60 (fit 48~64 안).
        songRepository.save(Song.builder()
                .title("잔잔한 발라드").artist("가수 가").releaseYear(2018)
                .keyOriginal(MusicalKey.C_MAJOR).bpm(72).mood(Mood.CALM)
                .language("ko").genre("발라드")
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(50).highMidi(60)
                .build());
        // 음역 미보유 곡 — fit 필터 시 제외 대상(null-range).
        songRepository.save(Song.builder()
                .title("음역없는 발라드").artist("가수 나")
                .keyOriginal(MusicalKey.C_MAJOR).mood(Mood.CALM)
                .language("ko").genre("발라드")
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build());
    }

    @Test
    @DisplayName("E2E: keyword 검색 — 제목 일치 곡을 wrapper items 로 반환")
    void e2e_searchByTitle_wrapper() {
        given()
                .when()
                .get("/api/v1/songs?keyword=벚꽃")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("items.size()", greaterThanOrEqualTo(1))
                .body("items[0].title", equalTo("벚꽃 엔딩"))
                .body("totalCount", greaterThanOrEqualTo(1));
    }

    @Test
    @DisplayName("E2E: 관련도 정렬 — 제목 일치가 가수 일치보다 상위")
    void e2e_relevance_titleBeatsArtist() {
        // given: 제목에 "발라드" 포함 곡 + 가수에 "발라드" 포함 곡
        songRepository.save(Song.builder()
                .title("그냥 노래").artist("발라드보이즈")
                .keyOriginal(MusicalKey.C_MAJOR).mood(Mood.CALM)
                .language("ko").genre("발라드")
                .metadataSource(MetadataSource.MANUAL_SEED)
                .lowMidi(55).highMidi(67)
                .build());

        given()
                .when()
                .get("/api/v1/songs?keyword=발라드&sort=relevance")
                .then()
                .statusCode(HttpStatus.OK.value())
                // 제목에 "발라드"가 들어간 곡이 가수 일치(발라드보이즈)보다 먼저.
                .body("items[0].title", org.hamcrest.Matchers.containsString("발라드"));
    }

    @Test
    @DisplayName("E2E: 초성 검색 — 'ㅂㄲ' prefix 로 벚꽃 엔딩 매칭")
    void e2e_chosungSearch() {
        given()
                .when()
                .get("/api/v1/songs?keyword=ㅂㄲ")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("items.title", hasItem("벚꽃 엔딩"));
    }

    @Test
    @DisplayName("E2E: 필터만(검색어 없이) difficulty=EASY & mood=CALM")
    void e2e_filterByDifficultyAndMood() {
        given()
                .when()
                .get("/api/v1/songs?difficulty=EASY&mood=CALM")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("items.difficulty", everyItem(equalTo("EASY")))
                .body("items.mood", everyItem(equalTo("CALM")))
                .body("items.title", hasItem("잔잔한 발라드"));
    }

    @Test
    @DisplayName("E2E: 음역 적합 필터 — lowMidi>=fitLow AND highMidi<=fitHigh, null-range 제외")
    void e2e_voiceFit() {
        given()
                .when()
                .get("/api/v1/songs?fitLow=48&fitHigh=64")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("items.lowMidi", everyItem(greaterThanOrEqualTo(48)))
                .body("items.highMidi", everyItem(lessThanOrEqualTo(64)))
                .body("items.title", hasItem("잔잔한 발라드"))
                .body("items.title", not(hasItem("음역없는 발라드")))
                .body("items.title", not(hasItem("벚꽃 엔딩")));
    }

    @Test
    @DisplayName("E2E: fitHigh 누락 → 400 (both-or-neither)")
    void e2e_voiceFit_missingHigh_returns400() {
        given()
                .when()
                .get("/api/v1/songs?fitLow=48")
                .then()
                .statusCode(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    @DisplayName("E2E: keyword 명시+빈 → 200 + 빈 items (기존 정책 유지)")
    void e2e_emptyKeyword_returnsEmpty() {
        given()
                .when()
                .get("/api/v1/songs?keyword=")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("items.size()", equalTo(0))
                .body("totalCount", equalTo(0));
    }

    @Test
    @DisplayName("E2E: size=101 → 400 (DoS 가드)")
    void e2e_sizeTooLarge_returns400() {
        given()
                .when()
                .get("/api/v1/songs?size=101")
                .then()
                .statusCode(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    @DisplayName("E2E: 미정의 difficulty 값 → 400")
    void e2e_invalidDifficulty_returns400() {
        given()
                .when()
                .get("/api/v1/songs?difficulty=SUPERHARD")
                .then()
                .statusCode(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    @DisplayName("E2E: 페이지네이션 — size=1 이면 hasNext=true")
    void e2e_pagination() {
        given()
                .when()
                .get("/api/v1/songs?genre=발라드&size=1&page=0")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("items.size()", equalTo(1))
                .body("size", equalTo(1))
                .body("hasNext", equalTo(true));
    }

    @Test
    @DisplayName("E2E: 자동완성 — q prefix 상위 N {id,title,artist} 만")
    void e2e_suggest() {
        given()
                .when()
                .get("/api/v1/songs/suggest?q=벚&limit=5")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("items.size()", lessThanOrEqualTo(5))
                .body("items.title", hasItem("벚꽃 엔딩"))
                .body("items[0]", hasKey("id"))
                .body("items[0]", hasKey("artist"))
                .body("items[0]", not(hasKey("difficulty")));
    }

    @Test
    @DisplayName("E2E: 자동완성 — q 비/공백이면 200 + 빈 items")
    void e2e_suggest_blankQuery() {
        given()
                .when()
                .get("/api/v1/songs/suggest?q=")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("items.size()", equalTo(0));
    }
}
