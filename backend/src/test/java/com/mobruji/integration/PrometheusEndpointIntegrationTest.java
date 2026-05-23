package com.mobruji.integration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import io.restassured.RestAssured;

/**
 * E2E: /actuator/prometheus 가 정상 노출 시 200 + Prometheus text format 응답을 반환하는지 검증 (이슈 #905).
 *
 * <p>{@link ActuatorExposureIntegrationTest} 는 base profile 의 보수적 default 에서 404 가
 * 유지되는지를 본다. 본 테스트는 그 반대 방향 — local/dev 처럼 exposure 에 prometheus 를
 * 명시 활성화했을 때 micrometer-registry-prometheus 의존이 실제로 endpoint 를 wiring 하는지
 * 회귀 가드 한다. (의존 누락 시 exposure 켜도 404 → 본 테스트가 잡아낸다.)
 *
 * <p>{@link AutoConfigureObservability} 는 Spring Boot 의 기본 test 정책(외부 metrics backend 비활성)
 * 을 우회해 prometheus export 를 활성화한다. 운영 환경(local profile + bootRun) 에서는 별도 플래그 없이
 * 동작한다 — test 격리 정책 때문에만 필요.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "management.server.port=",
                "management.endpoints.web.exposure.include=health,info,prometheus"
        })
@AutoConfigureObservability
@ActiveProfiles("test")
class PrometheusEndpointIntegrationTest {

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    @DisplayName("/actuator/prometheus 는 exposure 활성 시 200 + Prometheus text format 반환")
    void prometheusEndpointReturnsMetricsWhenExposed() {
        given()
                .when()
                .get("/actuator/prometheus")
                .then()
                .statusCode(HttpStatus.OK.value())
                .contentType(startsWith("text/plain"))
                .body(containsString("jvm_"));
    }
}
