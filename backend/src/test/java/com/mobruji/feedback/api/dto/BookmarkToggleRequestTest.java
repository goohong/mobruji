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
 * BookmarkToggleRequest 메타데이터 + Validator 실행 회귀 가드. LikeToggleRequestTest 와 동일 구조 (#666).
 * #948 후속에서 {@code SessionIdPatterns.UUID_V4} {@code @Pattern} 강제 — fixture sessionId UUIDv4 통일.
 */
class BookmarkToggleRequestTest {

    private static final String VALID_SESSION_ID = "550e8400-e29b-41d4-a716-446655449301";
    private static final String VALID_SESSION_ID_ALT = "550e8400-e29b-41d4-a716-446655449302";

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
    @DisplayName("sessionId: @NotBlank + @Size(max=64) + @Pattern(UUIDv4) 메타데이터 존재")
    void sessionIdValidationMetadata_present() throws NoSuchMethodException {
        assertThat(BookmarkToggleRequest.class.getDeclaredMethod("sessionId").getAnnotation(NotBlank.class))
                .isNotNull();

        final Size sessionIdSize = BookmarkToggleRequest.class
                .getDeclaredMethod("sessionId").getAnnotation(Size.class);
        assertThat(sessionIdSize).isNotNull();
        assertThat(sessionIdSize.max()).isEqualTo(64);

        final Pattern sessionIdPattern = BookmarkToggleRequest.class
                .getDeclaredMethod("sessionId").getAnnotation(Pattern.class);
        assertThat(sessionIdPattern).isNotNull();
        assertThat(sessionIdPattern.regexp()).isEqualTo(SessionIdPatterns.UUID_V4);
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
        final BookmarkToggleRequest bookmarkToggleRequest = new BookmarkToggleRequest(VALID_SESSION_ID, 7L);

        assertThat(bookmarkToggleRequest.sessionId()).isEqualTo(VALID_SESSION_ID);
        assertThat(bookmarkToggleRequest.songId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("Validator 실행: 정상 입력(UUIDv4 sessionId) → violation 없음")
    void validator_validInput_passes() {
        final BookmarkToggleRequest bookmarkToggleRequest = new BookmarkToggleRequest(VALID_SESSION_ID, 1L);

        final Set<ConstraintViolation<BookmarkToggleRequest>> violations = validator.validate(bookmarkToggleRequest);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Validator 실행: sessionId blank / songId 0/negative/null → violation 발생")
    void validator_invalidInputs_produceViolations() {
        assertThat(validator.validate(new BookmarkToggleRequest("", 1L))).isNotEmpty();
        assertThat(validator.validate(new BookmarkToggleRequest(VALID_SESSION_ID, 0L))).isNotEmpty();
        assertThat(validator.validate(new BookmarkToggleRequest(VALID_SESSION_ID_ALT, -1L))).isNotEmpty();
        assertThat(validator.validate(new BookmarkToggleRequest(VALID_SESSION_ID, null))).isNotEmpty();
    }

    @Test
    @DisplayName("Validator 실행 (#948): sessionId 비-UUIDv4 형식 → @Pattern violation")
    void validator_nonUuidV4SessionId_violates() {
        // SessionRotateRequest / VoiceRangeCreateRequest 와 동일한 형식 강제.
        assertThat(validator.validate(new BookmarkToggleRequest("session-b", 1L))).isNotEmpty();
        assertThat(validator.validate(new BookmarkToggleRequest("k6-load-1716543210-3", 1L))).isNotEmpty();
        assertThat(validator.validate(new BookmarkToggleRequest(
                "550E8400-E29B-41D4-A716-446655449301", 1L))).isNotEmpty();
        assertThat(validator.validate(new BookmarkToggleRequest("b".repeat(65), 1L))).isNotEmpty();
        assertThat(validator.validate(new BookmarkToggleRequest(VALID_SESSION_ID, 1L))).isEmpty();
    }
}
