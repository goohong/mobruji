package com.mobruji.integration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import io.restassured.RestAssured;

/**
 * GET /api/v1/mood-presets E2E (분위기 메이커 모드 F3 1단계 — #1562).
 *
 * <p>spec: docs/features/mood-mode.md §5-1, §5-2, §7. 프리셋→{@code Mood}/{@code preferredBpm}
 * 매핑의 BE 단일 출처를 검증한다. preferredBpm 은 {@code recommendation.tempo.moodDefaultBpm}
 * (test 프로파일이 main application.yml 상속)에서 프리셋의 Mood 키로 해석된 값:
 *
 * <pre>
 * PARTY → UPBEAT → 128
 * SINGALONG → POWERFUL → 140
 * EMOTIONAL → EMOTIONAL→ 80
 * ICEBREAKER → GROOVY → 110
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MoodPresetIntegrationTest {

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    @DisplayName("E2E: 프리셋 카탈로그 → 선언 순서(PARTY, SINGALONG, EMOTIONAL, ICEBREAKER) + Mood/preferredBpm 매핑")
    void e2e_moodPresets_catalog() {
        given()
                .when()
                .get("/api/v1/mood-presets")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("presets", hasSize(4))
                .body("presets.preset", contains("PARTY", "SINGALONG", "EMOTIONAL", "ICEBREAKER"))
                .body("presets[0].label", equalTo("회식 띄우기"))
                .body("presets[0].mood", equalTo("UPBEAT"))
                .body("presets[0].preferredBpm", equalTo(128))
                .body("presets[1].label", equalTo("떼창"))
                .body("presets[1].mood", equalTo("POWERFUL"))
                .body("presets[1].preferredBpm", equalTo(140))
                .body("presets[2].label", equalTo("감성"))
                .body("presets[2].mood", equalTo("EMOTIONAL"))
                .body("presets[2].preferredBpm", equalTo(80))
                .body("presets[3].label", equalTo("도입"))
                .body("presets[3].mood", equalTo("GROOVY"))
                .body("presets[3].preferredBpm", equalTo(110));
    }
}
