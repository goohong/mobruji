package com.mobruji.feedback.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.user.domain.SessionIdPatterns;

/**
 * LikeToggleRequest 메타데이터 + Validator 실행 회귀 가드.
 *
 * <p>record component 의 검증 어노테이션이 실수로 빠지면(혹은 max 가 바뀌면) 컴파일은 통과하지만
 * 런타임에 잘못된 입력이 통과될 수 있다. 본 테스트는 어노테이션 메타데이터 + 실제 Validator 실행
 * 두 축으로 회귀를 잠근다 (#666). #948 후속에서 {@code SessionIdPatterns.UUID_V4} {@code @Pattern}
 * 강제 — fixture sessionId 는 UUIDv4 로 통일.
 */
class LikeToggleRequestTest {

    private static final String VALID_SESSION_ID = "550e8400-e29b-41d4-a716-446655449201";
    private static final String VALID_SESSION_ID_ALT = "550e8400-e29b-41d4-a716-446655449202";

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        validatorFactory.close();
    }

    @Test
    @DisplayName("sessionId: @NotBlank + @Size(max=64) + @Pattern(UUIDv4) 메타데이터 존재 (DB column length=64 정합)")
    void sessionIdValidationMetadata_present() throws NoSuchMethodException {
        assertThat(LikeToggleRequest.class.getDeclaredMethod("sessionId").getAnnotation(NotBlank.class))
                .isNotNull();

        final Size sessionIdSize = LikeToggleRequest.class
                .getDeclaredMethod("sessionId").getAnnotation(Size.class);
        assertThat(sessionIdSize).isNotNull();
        assertThat(sessionIdSize.max()).isEqualTo(64);

        final Pattern sessionIdPattern = LikeToggleRequest.class
                .getDeclaredMethod("sessionId").getAnnotation(Pattern.class);
        assertThat(sessionIdPattern).isNotNull();
        assertThat(sessionIdPattern.regexp()).isEqualTo(SessionIdPatterns.UUID_V4);
    }

    @Test
    @DisplayName("songId: @NotNull + @Positive 메타데이터 존재")
    void songIdValidationMetadata_present() throws NoSuchMethodException {
        assertThat(LikeToggleRequest.class.getDeclaredMethod("songId").getAnnotation(NotNull.class))
                .isNotNull();
        assertThat(LikeToggleRequest.class.getDeclaredMethod("songId").getAnnotation(Positive.class))
                .isNotNull();
    }

    @Test
    @DisplayName("record component 직접 매핑: sessionId/songId 접근자가 입력값을 그대로 반환")
    void recordComponents_passthrough() {
        final LikeToggleRequest likeToggleRequest = new LikeToggleRequest(VALID_SESSION_ID, 42L);

        assertThat(likeToggleRequest.sessionId()).isEqualTo(VALID_SESSION_ID);
        assertThat(likeToggleRequest.songId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("Validator 실행: 정상 입력(UUIDv4 sessionId) → violation 없음")
    void validator_validInput_passes() {
        final LikeToggleRequest likeToggleRequest = new LikeToggleRequest(VALID_SESSION_ID, 1L);

        final Set<ConstraintViolation<LikeToggleRequest>> violations = validator.validate(likeToggleRequest);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Validator 실행: sessionId blank / songId 0/negative/null → violation 발생")
    void validator_invalidInputs_produceViolations() {
        assertThat(validator.validate(new LikeToggleRequest("", 1L))).isNotEmpty();
        assertThat(validator.validate(new LikeToggleRequest(VALID_SESSION_ID, 0L))).isNotEmpty();
        assertThat(validator.validate(new LikeToggleRequest(VALID_SESSION_ID_ALT, -1L))).isNotEmpty();
        assertThat(validator.validate(new LikeToggleRequest(VALID_SESSION_ID, null))).isNotEmpty();
    }

    @Test
    @DisplayName("Validator 실행 (#948): sessionId 비-UUIDv4 형식 → @Pattern violation")
    void validator_nonUuidV4SessionId_violates() {
        // SessionRotateRequest / VoiceRangeCreateRequest 와 동일한 형식 강제 — 임의 string 거부.
        assertThat(validator.validate(new LikeToggleRequest("session-a", 1L))).isNotEmpty();
        assertThat(validator.validate(new LikeToggleRequest("k6-load-1716543210-3", 1L))).isNotEmpty();
        // 대문자 UUID → reject (소문자 hex 강제)
        assertThat(validator.validate(new LikeToggleRequest(
                "550E8400-E29B-41D4-A716-446655449201", 1L))).isNotEmpty();
        // @Size(max=64) 단독 위반(65자 임의 문자열)도 Pattern 까지 동시 위반
        assertThat(validator.validate(new LikeToggleRequest("a".repeat(65), 1L))).isNotEmpty();
        // 유효 UUIDv4 → violation 없음 (대조군)
        assertThat(validator.validate(new LikeToggleRequest(VALID_SESSION_ID, 1L))).isEmpty();
    }
}
