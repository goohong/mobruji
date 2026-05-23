package com.mobruji.admin;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@link AdminTokenVerifier} 단위 테스트 — admin endpoint 토큰 게이트 분기 매트릭스 (#224 #228 / #631).
 *
 * <p>상수시간 비교 자체는 측정하기 어렵지만, {@link java.security.MessageDigest#isEqual} 분기
 * (길이 다름 / 내용 다름 / 동일) 와 입력 가드(null / blank / whitespace) 를 모두 커버한다.
 */
class AdminTokenVerifierTest {

    private static final String EXPECTED_TOKEN = "super-secret-admin-token";

    private final AdminTokenVerifier adminTokenVerifier = new AdminTokenVerifier(new AdminAuthProperties(
            EXPECTED_TOKEN));

    @Test
    @DisplayName("토큰 일치 → 예외 없음")
    void verify_matching_passes() {
        assertThatCode(() -> adminTokenVerifier.verify(EXPECTED_TOKEN))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("토큰 불일치 (같은 길이) → 401")
    void verify_mismatchSameLength_throws401() {
        final String wrongToken = "super-secret-admin-WRONG";
        assertThatThrownBy(() -> adminTokenVerifier.verify(wrongToken))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(throwable -> ((ResponseStatusException) throwable).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("토큰 길이 다름 (짧음) → 401 — MessageDigest.isEqual 길이 분기 커버")
    void verify_shorterToken_throws401() {
        assertThatThrownBy(() -> adminTokenVerifier.verify("short"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(throwable -> ((ResponseStatusException) throwable).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("토큰 길이 다름 (긺) → 401")
    void verify_longerToken_throws401() {
        assertThatThrownBy(() -> adminTokenVerifier.verify(EXPECTED_TOKEN + "-extra"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(throwable -> ((ResponseStatusException) throwable).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("null 헤더 → 401 (missing admin token)")
    void verify_nullToken_throws401() {
        assertThatThrownBy(() -> adminTokenVerifier.verify(null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(throwable -> ((ResponseStatusException) throwable).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("빈 문자열 → 401")
    void verify_emptyToken_throws401() {
        assertThatThrownBy(() -> adminTokenVerifier.verify(""))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(throwable -> ((ResponseStatusException) throwable).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("whitespace 만 → 401 (isBlank 가드)")
    void verify_whitespaceToken_throws401() {
        assertThatThrownBy(() -> adminTokenVerifier.verify("   \t  "))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(throwable -> ((ResponseStatusException) throwable).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("UTF-8 (한글 포함) 토큰 정상 비교")
    void verify_utf8Matching_passes() {
        final String utf8Token = "관리자-토큰-가나다";
        final AdminTokenVerifier utf8Verifier = new AdminTokenVerifier(new AdminAuthProperties(utf8Token));
        assertThatCode(() -> utf8Verifier.verify(utf8Token))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("UTF-8 토큰 미세 차이 → 401 (byte 수준 비교 정확성)")
    void verify_utf8Mismatch_throws401() {
        final String utf8Token = "관리자-토큰-가나다";
        final AdminTokenVerifier utf8Verifier = new AdminTokenVerifier(new AdminAuthProperties(utf8Token));
        assertThatThrownBy(() -> utf8Verifier.verify("관리자-토큰-가나라"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(throwable -> ((ResponseStatusException) throwable).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("토큰 원문이 예외 reason 에 노출되지 않음 (민감정보 보호)")
    void verify_tokenNotLeakedInException() {
        assertThatThrownBy(() -> adminTokenVerifier.verify("leaked-token-value-xyz"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(throwable -> {
                    final ResponseStatusException responseStatusException = (ResponseStatusException) throwable;
                    final String reason = responseStatusException.getReason();
                    assert reason != null;
                    assert !reason.contains("leaked-token-value-xyz");
                    assert !reason.contains(EXPECTED_TOKEN);
                });
    }
}
