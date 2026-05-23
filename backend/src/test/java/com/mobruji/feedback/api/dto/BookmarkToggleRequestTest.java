package com.mobruji.feedback.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BookmarkToggleRequest 메타데이터 회귀 가드. LikeToggleRequestTest 와 동일 구조.
 */
class BookmarkToggleRequestTest {

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
}
