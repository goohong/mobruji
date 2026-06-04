package com.mobruji.integration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.mobruji.song.application.AudioAnalysisRunner;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

import io.restassured.RestAssured;

/**
 * E2E: {@code POST /api/v1/admin/songs/audio-backfill} — admin 게이트 + dryRun preview shape.
 *
 * <p>이슈 #1757. {@code X-Admin-Token} 필수(누락/불일치 → 401). dryRun=true 는 분석/적용 없이 음역대 미보유
 * 후보 집계만 반환한다(영속 없음). 외부 Python/yt-dlp 호출은 {@link AudioAnalysisRunner} 를 {@link MockitoBean}
 * 으로 대체해 CI 에서 실제 호출을 막는다.
 *
 * <p>비동기 backfill 의 실제 DB 반영(음역대 미보유 곡 → low_midi NOT NULL)은 곡당 자체분석 글로벌 통계
 * (LAST_BACKFILL_COMPLETED_AT) 를 건드려 단일 JVM fork 의 다른 통합 테스트(stats null 단언)를 오염시키므로,
 * 비동기 위임 경로는 {@code AudioBackfillExecutorTest} 단위로 검증한다. 라이브 backfill 효과 확인은 머지 후 운영 검증.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AudioBackfillOnDemandE2ETest {

    // application-test.yml 의 mobruji.admin.token 과 동일해야 한다.
    private static final String ADMIN_TOKEN = "test-admin-token";
    private static final String TITLE = "음역대미보유곡";
    private static final String ARTIST = "테스트가수";

    @LocalServerPort
    private int port;

    @Autowired
    private SongRepository songRepository;

    @MockitoBean
    private AudioAnalysisRunner audioAnalysisRunner;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        songRepository.deleteAll();
        // lowMidi/highMidi 미설정 = 음역대 미보유 곡 (추천 풀에서 빠짐 → backfill 대상)
        songRepository.save(Song.builder()
                .title(TITLE).artist(ARTIST)
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build());
    }

    @Test
    @DisplayName("E2E: dryRun=true → 200 + 음역대 미보유 후보 집계 (영속 없음)")
    void e2e_dryRun_returnsCandidates() {
        given()
                .header("X-Admin-Token", ADMIN_TOKEN)
                .when()
                .post("/api/v1/admin/songs/audio-backfill?dryRun=true")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("candidates", equalTo(1))
                .body("selected", equalTo(1))
                .body("dryRun", equalTo(true))
                .body("started", equalTo(false));

        // dryRun 이므로 분석/적용이 없어 음역대는 여전히 미보유여야 한다.
        final Song song = songRepository.findByTitleAndArtist(TITLE, ARTIST).orElseThrow();
        Assertions.assertThat(song.getLowMidi()).isNull();
    }

    @Test
    @DisplayName("E2E: X-Admin-Token 헤더 누락 → 401")
    void e2e_missingToken_returns401() {
        given()
                .when()
                .post("/api/v1/admin/songs/audio-backfill")
                .then()
                .statusCode(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    @DisplayName("E2E: X-Admin-Token 값 불일치 → 401")
    void e2e_invalidToken_returns401() {
        given()
                .header("X-Admin-Token", "wrong-token")
                .when()
                .post("/api/v1/admin/songs/audio-backfill")
                .then()
                .statusCode(HttpStatus.UNAUTHORIZED.value());
    }
}
