package com.mobruji.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import com.mobruji.user.infrastructure.UserAuthTokenRepository;
import com.mobruji.user.infrastructure.UserRepository;

import io.restassured.RestAssured;

/**
 * E2E: 이메일 회원가입/로그인 + 음역대 프로필 영속.
 *
 * <p>spec: 인증·음역대 프로필 영속 (#1491). CLAUDE.md §4 — 신규 endpoint 는 RestAssured E2E
 * 성공 케이스 필수.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UserAuthIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserAuthTokenRepository userAuthTokenRepository;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        userAuthTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("E2E: signup → /me → login 전체 흐름 — 이메일 소문자 정규화 + 프로필 영속 + 토큰 원문 미저장")
    void e2e_signupThenMeThenLogin_persistsProfile() {
        // given / when: 대문자 섞인 이메일로 가입
        final String signupToken = given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body("""
                        {"email":"Singer@Example.com","password":"correct-horse","gender":"MALE",
                         "vocalRangeLowMidi":48,"vocalRangeHighMidi":72}
                        """)
                .when()
                .post("/api/v1/users/signup")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .body("userId", notNullValue())
                .body("email", equalTo("singer@example.com"))
                .body("token", notNullValue())
                .body("tokenExpiresAt", notNullValue())
                .extract().path("token");

        // then: 프로필 영속 검증 (재입력 불필요)
        given()
                .header("Authorization", "Bearer " + signupToken)
                .when()
                .get("/api/v1/users/me")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("email", equalTo("singer@example.com"))
                .body("authProvider", equalTo("LOCAL"))
                .body("gender", equalTo("MALE"))
                .body("vocalRangeLowMidi", equalTo(48))
                .body("vocalRangeHighMidi", equalTo(72));

        // then: 토큰 원문이 평문으로 저장되지 않음 (at-rest 보호)
        assertThat(userAuthTokenRepository.findByTokenHash(signupToken)).isEmpty();
        assertThat(userRepository.count()).isEqualTo(1L);

        // when: 동일 자격으로 로그인 → 새 토큰 발급 (토큰 2건)
        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body("""
                        {"email":"singer@example.com","password":"correct-horse"}
                        """)
                .when()
                .post("/api/v1/users/login")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("email", equalTo("singer@example.com"))
                .body("token", notNullValue());

        assertThat(userAuthTokenRepository.count()).isEqualTo(2L);
    }

    @Test
    @DisplayName("E2E: PATCH /me 는 음역대/성별 프로필을 갱신한다")
    void e2e_patchMe_updatesProfile() {
        final String token = signup("update@example.com", "correct-horse");

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .header("Authorization", "Bearer " + token)
                .body("""
                        {"gender":"FEMALE","vocalRangeLowMidi":41,"vocalRangeHighMidi":65}
                        """)
                .when()
                .patch("/api/v1/users/me")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("gender", equalTo("FEMALE"))
                .body("vocalRangeLowMidi", equalTo(41))
                .body("vocalRangeHighMidi", equalTo(65));
    }

    @Test
    @DisplayName("E2E: 이메일 중복 가입 → 409")
    void e2e_duplicateEmail_returns409() {
        signup("dup@example.com", "correct-horse");

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body("""
                        {"email":"dup@example.com","password":"another-pass"}
                        """)
                .when()
                .post("/api/v1/users/signup")
                .then()
                .statusCode(HttpStatus.CONFLICT.value());
    }

    @Test
    @DisplayName("E2E: 틀린 비밀번호 로그인 → 401")
    void e2e_wrongPassword_returns401() {
        signup("wrongpw@example.com", "correct-horse");

        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body("""
                        {"email":"wrongpw@example.com","password":"wrong-pass"}
                        """)
                .when()
                .post("/api/v1/users/login")
                .then()
                .statusCode(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    @DisplayName("E2E: 미등록 이메일 로그인 → 401 (enumeration 방어 — 틀린 비밀번호와 동일)")
    void e2e_unknownEmail_returns401() {
        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body("""
                        {"email":"nobody@example.com","password":"correct-horse"}
                        """)
                .when()
                .post("/api/v1/users/login")
                .then()
                .statusCode(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    @DisplayName("E2E: 토큰 없이 /me → 401")
    void e2e_meWithoutToken_returns401() {
        given()
                .when()
                .get("/api/v1/users/me")
                .then()
                .statusCode(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    @DisplayName("E2E: 무효 토큰 /me → 401")
    void e2e_meInvalidToken_returns401() {
        given()
                .header("Authorization", "Bearer not-a-real-token")
                .when()
                .get("/api/v1/users/me")
                .then()
                .statusCode(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    @DisplayName("E2E: 8자 미만 비밀번호 가입 → 400")
    void e2e_shortPassword_returns400() {
        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body("""
                        {"email":"short@example.com","password":"abc"}
                        """)
                .when()
                .post("/api/v1/users/signup")
                .then()
                .statusCode(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    @DisplayName("E2E: 이메일 형식 위반 가입 → 400")
    void e2e_invalidEmail_returns400() {
        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body("""
                        {"email":"not-an-email","password":"correct-horse"}
                        """)
                .when()
                .post("/api/v1/users/signup")
                .then()
                .statusCode(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    @DisplayName("E2E: 음역대 한쪽만 입력한 가입 → 400 (교차 검증)")
    void e2e_partialVocalRange_returns400() {
        given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body("""
                        {"email":"partial@example.com","password":"correct-horse","vocalRangeLowMidi":48}
                        """)
                .when()
                .post("/api/v1/users/signup")
                .then()
                .statusCode(HttpStatus.BAD_REQUEST.value());
    }

    private String signup(final String email, final String password) {
        return given()
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body("""
                        {"email":"%s","password":"%s"}
                        """.formatted(email, password))
                .when()
                .post("/api/v1/users/signup")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .extract().path("token");
    }
}
