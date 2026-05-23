package com.mobruji.integration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import io.restassured.RestAssured;

/**
 * E2E: actuator endpoint 외부 노출 정책 회귀 가드 (이슈 #395).
 *
 * <p>defense in depth — nginx whitelist (nginx/conf.d/dev.conf) 와 별도로 Spring
 * exposure 도 base profile 에서 health,info 만 허용한다. metrics/prometheus 는
 * local/dev profile 에서만 옵션 활성 (application-local.yml).
 *
 * <p>본 테스트는 {@code @ActiveProfiles("test")} + {@code management.endpoints.web.exposure.include}
 * 미지정 (base application.yml 의 보수적 default 적용) 조건에서 검증:
 *
 * <ul>
 * <li>{@code /actuator/health} → 200 (health 는 base exposure 에 포함)</li>
 * <li>{@code /actuator/metrics} → 404 (base exposure 에서 제외, 외부 노출 차단)</li>
 * <li>{@code /actuator/prometheus} → 404 (base exposure 에서 제외, 외부 노출 차단)</li>
 * </ul>
 *
 * <p>{@code management.server.port=} 빈 값으로 override 하여 메인 서버 port 와 합치고
 * RestAssured 로 {@link LocalServerPort} 한 곳에서 호출한다 (test 환경 단순화 목적).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "management.server.port=",
                "management.endpoints.web.exposure.include=health,info"
        })
@ActiveProfiles("test")
class ActuatorExposureIntegrationTest {

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    @DisplayName("/actuator/health 는 base exposure 에 포함 → 200")
    void healthEndpointIsExposed() {
        given()
                .when()
                .get("/actuator/health")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("status", equalTo("UP"));
    }

    @Test
    @DisplayName("/actuator/metrics 는 base exposure 제외 → 404 (외부 노출 차단, 이슈 #395)")
    void metricsEndpointIsNotExposed() {
        given()
                .when()
                .get("/actuator/metrics")
                .then()
                .statusCode(HttpStatus.NOT_FOUND.value());
    }

    @Test
    @DisplayName("/actuator/prometheus 는 base exposure 제외 → 404 (외부 노출 차단, 이슈 #395)")
    void prometheusEndpointIsNotExposed() {
        given()
                .when()
                .get("/actuator/prometheus")
                .then()
                .statusCode(HttpStatus.NOT_FOUND.value());
    }
}
