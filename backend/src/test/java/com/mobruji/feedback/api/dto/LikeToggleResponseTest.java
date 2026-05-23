package com.mobruji.feedback.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.feedback.application.ToggleResult;

/**
 * {@link LikeToggleResponse#from(ToggleResult)} factory 회귀 가드.
 *
 * <p>ToggleResult.active 가 liked 필드로 매핑되는 단순 경로지만, factory 변경 시 fe 가 받는 응답 의미(liked=true → 신규
 * 생성)가 깨지면 사용자에게 즉시 보인다.
 */
class LikeToggleResponseTest {

    @Test
    @DisplayName("from: active=true → liked=true, songId 그대로 매핑 (신규 좋아요 생성)")
    void from_activeTrue_mapsToLiked() {
        // given
        final ToggleResult toggleResult = new ToggleResult(true, 42L);

        // when
        final LikeToggleResponse likeToggleResponse = LikeToggleResponse.from(toggleResult);

        // then
        assertThat(likeToggleResponse.liked()).isTrue();
        assertThat(likeToggleResponse.songId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("from: active=false → liked=false (기존 좋아요 삭제 경로)")
    void from_activeFalse_mapsToUnliked() {
        // given
        final ToggleResult toggleResult = new ToggleResult(false, 7L);

        // when
        final LikeToggleResponse likeToggleResponse = LikeToggleResponse.from(toggleResult);

        // then
        assertThat(likeToggleResponse.liked()).isFalse();
        assertThat(likeToggleResponse.songId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("record component passthrough: liked/songId 접근자가 입력값을 그대로 반환")
    void recordComponents_passthrough() {
        // given / when
        final LikeToggleResponse likeToggleResponse = new LikeToggleResponse(true, 100L);

        // then
        assertThat(likeToggleResponse.liked()).isTrue();
        assertThat(likeToggleResponse.songId()).isEqualTo(100L);
    }
}
