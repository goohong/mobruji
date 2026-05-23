package com.mobruji.feedback.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * LikeToggleRequest 메타데이터 회귀 가드.
 *
 * <p>record component 의 검증 어노테이션이 실수로 빠지면(혹은 max 가 바뀌면) 컴파일은 통과하지만
 * 런타임에 잘못된 입력이 통과될 수 있다. 본 테스트는 어노테이션 메타데이터를 직접 검증하여 회귀를 잠근다.
 */
class LikeToggleRequestTest {

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
}
