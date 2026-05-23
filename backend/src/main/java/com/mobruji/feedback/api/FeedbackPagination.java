package com.mobruji.feedback.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Feedback API(Like/Bookmark) 공통 페이지네이션 상수 + 검증 유틸.
 *
 * <p>spec {@code recommendation-history-and-feedback.md §5-2} — {@code Page<>} 응답.
 * 최대 size 는 의도치 않은 대용량 응답(메모리/네트워크 부담)을 막기 위한 가드.
 *
 * <p>{@link LikeController}/{@link BookmarkController} 가 동일 검증을 중복 보유하던 것을 단일 출처로
 * 통합한다(#859). 향후 페이지네이션 정책 변경 시 한 곳만 수정하면 두 컨트롤러에 일관 적용된다.
 */
public final class FeedbackPagination {

    public static final int DEFAULT_PAGE_SIZE = 20;

    public static final int MAX_PAGE_SIZE = 100;

    private FeedbackPagination() {
        // utility class
    }

    /**
     * page/size 파라미터 공통 검증. 위반 시 400 BAD_REQUEST.
     *
     * @param page 0-base 페이지 인덱스 (>=0)
     * @param size 페이지 사이즈 (1..{@value #MAX_PAGE_SIZE})
     */
    public static void validate(final int page, final int size) {
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be >= 0");
        }
        if (size < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size must be >= 1");
        }
        if (size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "size must be <= " + MAX_PAGE_SIZE);
        }
    }
}
