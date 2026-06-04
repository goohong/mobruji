package com.mobruji.integration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.mobruji.song.application.musicbrainz.MusicBrainzClient;
import com.mobruji.song.application.musicbrainz.MusicBrainzMatch;
import com.mobruji.song.domain.MetadataSource;
import com.mobruji.song.domain.MusicalKey;
import com.mobruji.song.domain.Song;
import com.mobruji.song.infrastructure.SongRepository;

import io.restassured.RestAssured;

/**
 * E2E: {@code POST /api/v1/admin/songs/musicbrainz-backfill} — admin 게이트 + 응답 shape.
 *
 * <p>spec {@code musicbrainz-integration.md} §5-2/§7. {@code X-Admin-Token} 필수(누락/불일치 → 401).
 * 외부 MusicBrainz 호출은 {@link MusicBrainzClient} 를 {@link MockitoBean} 으로 대체해 CI 에서 실제 호출을 막는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MusicBrainzBackfillIntegrationTest {

    // application-test.yml 의 mobruji.admin.token 과 동일해야 한다.
    private static final String ADMIN_TOKEN = "test-admin-token";

    @LocalServerPort
    private int port;

    @Autowired
    private SongRepository songRepository;

    @MockitoBean
    private MusicBrainzClient musicBrainzClient;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        songRepository.deleteAll();
        songRepository.save(Song.builder()
                .title("좋니").artist("윤종신")
                .keyOriginal(MusicalKey.C_MAJOR)
                .metadataSource(MetadataSource.MANUAL_SEED)
                .build());
    }

    @Test
    @DisplayName("E2E: X-Admin-Token 정상 + dryRun=true → 200 + 매칭 후보 집계 (영속 없음)")
    void e2e_dryRun_returnsCandidates() {
        final MusicBrainzMatch match = new MusicBrainzMatch("b9ad642e-b012-41c7-b72a-42cf3437f9d8", "KRA401700001", 95);
        when(musicBrainzClient.searchTopRecording("좋니", "윤종신")).thenReturn(Optional.of(match));

        given()
                .header("X-Admin-Token", ADMIN_TOKEN)
                .when()
                .post("/api/v1/admin/songs/musicbrainz-backfill?dryRun=true&minScore=90")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("processed", equalTo(1))
                .body("matched", equalTo(1))
                .body("aborted", equalTo(false));

        // dryRun 이므로 DB 에는 mbId 가 적용되지 않아야 한다.
        assertNoMbIdPersisted();
    }

    @Test
    @DisplayName("E2E: 무매칭이면 notFound 집계 (실제 영속 없음)")
    void e2e_notFound_counts() {
        when(musicBrainzClient.searchTopRecording(anyString(), anyString())).thenReturn(Optional.empty());

        given()
                .header("X-Admin-Token", ADMIN_TOKEN)
                .when()
                .post("/api/v1/admin/songs/musicbrainz-backfill?dryRun=true")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("processed", equalTo(1))
                .body("notFound", equalTo(1));
    }

    @Test
    @DisplayName("E2E: X-Admin-Token 헤더 누락 → 401")
    void e2e_missingToken_returns401() {
        given()
                .when()
                .post("/api/v1/admin/songs/musicbrainz-backfill")
                .then()
                .statusCode(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    @DisplayName("E2E: X-Admin-Token 값 불일치 → 401")
    void e2e_invalidToken_returns401() {
        given()
                .header("X-Admin-Token", "wrong-token")
                .when()
                .post("/api/v1/admin/songs/musicbrainz-backfill")
                .then()
                .statusCode(HttpStatus.UNAUTHORIZED.value());
    }

    private void assertNoMbIdPersisted() {
        final boolean anyMbId = songRepository.findAll().stream().anyMatch(song -> song.getMbId() != null);
        org.assertj.core.api.Assertions.assertThat(anyMbId).isFalse();
    }
}
