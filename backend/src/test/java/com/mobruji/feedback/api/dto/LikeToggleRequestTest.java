package com.mobruji.feedback.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * LikeToggleRequest 메타데이터 + Validator 실행 회귀 가드.
 *
 * <p>record component 의 검증 어노테이션이 실수로 빠지면(혹은 max 가 바뀌면) 컴파일은 통과하지만
 * 런타임에 잘못된 입력이 통과될 수 있다. 본 테스트는 어노테이션 메타데이터 + 실제 Validator 실행
 * 두 축으로 회귀를 잠근다 (#666).
 */
class LikeToggleRequestTest {

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
    @DisplayName("sessionId: @NotBlank + @Size(max=64) 메타데이터 존재 (DB column length=64 정합)")
    void sessionIdValidationMetadata_present() throws NoSuchMethodException {
        assertThat(LikeToggleRequest.class.getDeclaredMethod("sessionId").getAnnotation(NotBlank.class))
                .isNotNull();

        final Size sessionIdSize = LikeToggleRequest.class
                .getDeclaredMethod("sessionId").getAnnotation(Size.class);
        assertThat(sessionIdSize).isNotNull();
        assertThat(sessionIdSize.max()).isEqualTo(64);
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
        final LikeToggleRequest likeToggleRequest = new LikeToggleRequest("session-a", 42L);

        assertThat(likeToggleRequest.sessionId()).isEqualTo("session-a");
        assertThat(likeToggleRequest.songId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("Validator 실행: 정상 입력(64자 경계) → violation 없음")
    void validator_validBoundaryInput_passes() {
        final String sessionId64 = "a".repeat(64);
        final LikeToggleRequest likeToggleRequest = new LikeToggleRequest(sessionId64, 1L);

        final Set<ConstraintViolation<LikeToggleRequest>> violations = validator.validate(likeToggleRequest);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Validator 실행: sessionId 65자 / blank / songId 0/negative → violation 발생")
    void validator_invalidInputs_produceViolations() {
        assertThat(validator.validate(new LikeToggleRequest("a".repeat(65), 1L))).isNotEmpty();
        assertThat(validator.validate(new LikeToggleRequest("", 1L))).isNotEmpty();
        assertThat(validator.validate(new LikeToggleRequest("session-a", 0L))).isNotEmpty();
        assertThat(validator.validate(new LikeToggleRequest("session-a", -1L))).isNotEmpty();
        assertThat(validator.validate(new LikeToggleRequest("session-a", null))).isNotEmpty();
    }
}
