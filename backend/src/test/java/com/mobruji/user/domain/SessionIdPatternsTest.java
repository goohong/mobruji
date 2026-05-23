package com.mobruji.user.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link SessionIdPatterns} 회귀 가드.
 *
 * <p>주 책임은 두 가지: (a) {@link SessionIdPatterns#UUID_V4} regex 가 표준 UUIDv4 toString 형식을
 * 정확히 매치하며 명백한 비-UUID 값을 거부하는지, (b) {@link SessionIdPatterns#randomUuidV4()} 헬퍼
 * 출력이 자체 regex 를 항상 통과하는지. 후자는 test fixture / k6 헬퍼 자바 포팅 시 hex hand-craft 를
 * 없애기 위한 단일 진입점 — 출력이 regex 와 어긋나면 fixture 전체가 깨진다.
 */
class SessionIdPatternsTest {

    private static final int RANDOM_SAMPLE_COUNT = 200;
    private static final Pattern PATTERN = Pattern.compile(SessionIdPatterns.UUID_V4);

    @Test
    @DisplayName("UUID_V4: 표준 UUID.randomUUID().toString() 결과를 매치한다")
    void uuidV4Pattern_matchesStandardUuidToString() {
        final String standardUuid = UUID.randomUUID().toString();

        final boolean matches = PATTERN.matcher(standardUuid).matches();

        assertThat(matches)
                .as("UUID.randomUUID().toString() 결과는 UUID_V4 regex 와 정확히 매치해야 한다")
                .isTrue();
    }

    @Test
    @DisplayName("UUID_V4: 대문자 hex / 길이 부족 / 임의 문자열 등 비-UUID 값을 거부한다")
    void uuidV4Pattern_rejectsInvalidValues() {
        final String upperCase = "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA";
        final String tooShort = "12345678-1234-1234-1234-12345678";
        final String missingHyphens = "123456781234123412341234567890ab";
        final String plainText = "anonymous-session-1";
        final String emptyString = "";

        assertThat(PATTERN.matcher(upperCase).matches())
                .as("대문자 hex 는 거부 (regex 는 소문자만 허용)")
                .isFalse();
        assertThat(PATTERN.matcher(tooShort).matches())
                .as("마지막 그룹 길이 < 12 는 거부")
                .isFalse();
        assertThat(PATTERN.matcher(missingHyphens).matches())
                .as("hyphen 누락은 거부")
                .isFalse();
        assertThat(PATTERN.matcher(plainText).matches())
                .as("임의 문자열은 거부")
                .isFalse();
        assertThat(PATTERN.matcher(emptyString).matches())
                .as("빈 문자열은 거부")
                .isFalse();
    }

    @Test
    @DisplayName("randomUuidV4: 출력이 항상 UUID_V4 regex 를 통과한다 (200회 샘플)")
    void randomUuidV4_outputAlwaysMatchesPattern() {
        IntStream.range(0, RANDOM_SAMPLE_COUNT).forEach(index -> {
            final String generated = SessionIdPatterns.randomUuidV4();

            assertThat(PATTERN.matcher(generated).matches())
                    .as("sample %d (%s) 가 UUID_V4 regex 매치 실패", index, generated)
                    .isTrue();
        });
    }

    @Test
    @DisplayName("randomUuidV4: 연속 호출 시 서로 다른 값을 반환한다 (UUID.randomUUID() 위임 확인)")
    void randomUuidV4_returnsDistinctValuesOnSuccessiveCalls() {
        final String first = SessionIdPatterns.randomUuidV4();
        final String second = SessionIdPatterns.randomUuidV4();

        assertThat(first)
                .as("randomUuidV4 는 호출마다 새 UUID 를 반환해야 한다")
                .isNotEqualTo(second);
    }

    @Test
    @DisplayName("matches: null 입력은 false (NotBlank 책임은 caller)")
    void matches_returnsFalseForNullInput() {
        final boolean result = SessionIdPatterns.matches(null);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("matches: randomUuidV4 출력은 항상 true 를 반환한다")
    void matches_returnsTrueForRandomUuidV4Output() {
        final String generated = SessionIdPatterns.randomUuidV4();

        final boolean result = SessionIdPatterns.matches(generated);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("matches: 대문자 hex UUID 는 false")
    void matches_returnsFalseForUpperCaseHex() {
        final String upperCase = UUID.randomUUID().toString().toUpperCase();

        final boolean result = SessionIdPatterns.matches(upperCase);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("UUID_V4_MESSAGE: 빈 문자열이 아니다 (Bean Validation 메시지로 사용 가능)")
    void uuidV4Message_isNonEmpty() {
        assertThat(SessionIdPatterns.UUID_V4_MESSAGE).isNotBlank();
    }
}
