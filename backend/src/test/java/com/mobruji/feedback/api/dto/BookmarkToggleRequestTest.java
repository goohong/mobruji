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
 * BookmarkToggleRequest 메타데이터 + Validator 실행 회귀 가드. LikeToggleRequestTest 와 동일 구조 (#666).
 */
class BookmarkToggleRequestTest {

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
    @DisplayName("sessionId: @NotBlank + @Size(max=64) 메타데이터 존재")
    void sessionIdValidationMetadata_present() throws NoSuchMethodException {
        assertThat(BookmarkToggleRequest.class.getDeclaredMethod("sessionId").getAnnotation(NotBlank.class))
                .isNotNull();

        final Size sessionIdSize = BookmarkToggleRequest.class
                .getDeclaredMethod("sessionId").getAnnotation(Size.class);
        assertThat(sessionIdSize).isNotNull();
        assertThat(sessionIdSize.max()).isEqualTo(64);
    }

    @Test
    @DisplayName("songId: @NotNull + @Positive 메타데이터 존재")
    void songIdValidationMetadata_present() throws NoSuchMethodException {
        assertThat(BookmarkToggleRequest.class.getDeclaredMethod("songId").getAnnotation(NotNull.class))
                .isNotNull();
        assertThat(BookmarkToggleRequest.class.getDeclaredMethod("songId").getAnnotation(Positive.class))
                .isNotNull();
    }

    @Test
    @DisplayName("record component 직접 매핑: sessionId/songId 접근자가 입력값을 그대로 반환")
    void recordComponents_passthrough() {
        final BookmarkToggleRequest bookmarkToggleRequest = new BookmarkToggleRequest("session-b", 7L);

        assertThat(bookmarkToggleRequest.sessionId()).isEqualTo("session-b");
        assertThat(bookmarkToggleRequest.songId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("Validator 실행: 정상 입력(64자 경계) → violation 없음")
    void validator_validBoundaryInput_passes() {
        final String sessionId64 = "b".repeat(64);
        final BookmarkToggleRequest bookmarkToggleRequest = new BookmarkToggleRequest(sessionId64, 1L);

        final Set<ConstraintViolation<BookmarkToggleRequest>> violations = validator.validate(bookmarkToggleRequest);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Validator 실행: sessionId 65자 / blank / songId 0/negative → violation 발생")
    void validator_invalidInputs_produceViolations() {
        assertThat(validator.validate(new BookmarkToggleRequest("b".repeat(65), 1L))).isNotEmpty();
        assertThat(validator.validate(new BookmarkToggleRequest("", 1L))).isNotEmpty();
        assertThat(validator.validate(new BookmarkToggleRequest("session-b", 0L))).isNotEmpty();
        assertThat(validator.validate(new BookmarkToggleRequest("session-b", -1L))).isNotEmpty();
        assertThat(validator.validate(new BookmarkToggleRequest("session-b", null))).isNotEmpty();
    }
}
