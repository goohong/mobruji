package com.mobruji.feedback.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.feedback.application.ToggleResult;

/**
 * {@link BookmarkToggleResponse#from(ToggleResult)} factory 회귀 가드.
 *
 * <p>{@link LikeToggleResponseTest} 와 동일 패턴 — active=true → bookmarked=true.
 */
class BookmarkToggleResponseTest {

    @Test
    @DisplayName("from: active=true → bookmarked=true, songId 그대로 매핑 (신규 북마크 생성)")
    void from_activeTrue_mapsToBookmarked() {
        // given
        final ToggleResult toggleResult = new ToggleResult(true, 42L);

        // when
        final BookmarkToggleResponse bookmarkToggleResponse = BookmarkToggleResponse.from(toggleResult);

        // then
        assertThat(bookmarkToggleResponse.bookmarked()).isTrue();
        assertThat(bookmarkToggleResponse.songId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("from: active=false → bookmarked=false (기존 북마크 삭제 경로)")
    void from_activeFalse_mapsToUnbookmarked() {
        // given
        final ToggleResult toggleResult = new ToggleResult(false, 7L);

        // when
        final BookmarkToggleResponse bookmarkToggleResponse = BookmarkToggleResponse.from(toggleResult);

        // then
        assertThat(bookmarkToggleResponse.bookmarked()).isFalse();
        assertThat(bookmarkToggleResponse.songId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("record component passthrough: bookmarked/songId 접근자가 입력값을 그대로 반환")
    void recordComponents_passthrough() {
        // given / when
        final BookmarkToggleResponse bookmarkToggleResponse = new BookmarkToggleResponse(true, 100L);

        // then
        assertThat(bookmarkToggleResponse.bookmarked()).isTrue();
        assertThat(bookmarkToggleResponse.songId()).isEqualTo(100L);
    }
}
