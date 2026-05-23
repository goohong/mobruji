package com.mobruji.feedback.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@link FeedbackPagination#validate(int, int)} 가드.
 *
 * <p>spec recommendation-history-and-feedback.md §5-2 — page/size 입력 가드. {@link LikeController}/{@link
 * BookmarkController} 에서 동일 검증을 위임하므로 단일 출처(#859)의 회귀를 막는다.
 */
class FeedbackPaginationTest {

    @Test
    @DisplayName("validate: 정상 page=0 size=20 → 예외 없음")
    void validate_withValidArgs_noException() {
        assertThatCode(() -> FeedbackPagination.validate(0, 20))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validate: page=-1 → BAD_REQUEST")
    void validate_withNegativePage_throwsBadRequest() {
        // when / then
        assertThatThrownBy(() -> FeedbackPagination.validate(-1, 20))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("page");
    }

    @Test
    @DisplayName("validate: size=0 → BAD_REQUEST")
    void validate_withZeroSize_throwsBadRequest() {
        // when / then
        assertThatThrownBy(() -> FeedbackPagination.validate(0, 0))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("size");
    }

    @Test
    @DisplayName("validate: size=MAX+1 → BAD_REQUEST (MAX_PAGE_SIZE 초과)")
    void validate_withSizeOverMax_throwsBadRequest() {
        // given
        final int overMax = FeedbackPagination.MAX_PAGE_SIZE + 1;

        // when / then
        assertThatThrownBy(() -> FeedbackPagination.validate(0, overMax))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("size")
                .hasMessageContaining(String.valueOf(FeedbackPagination.MAX_PAGE_SIZE));
    }

    @Test
    @DisplayName("validate: size=MAX_PAGE_SIZE 경계 → 예외 없음")
    void validate_withSizeAtMax_noException() {
        assertThatCode(() -> FeedbackPagination.validate(0, FeedbackPagination.MAX_PAGE_SIZE))
                .doesNotThrowAnyException();
    }
}
