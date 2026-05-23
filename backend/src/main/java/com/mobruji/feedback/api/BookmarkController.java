package com.mobruji.feedback.api;

import java.util.List;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mobruji.auth.SessionAuthGuard;
import com.mobruji.feedback.api.dto.BookmarkListResponse;
import com.mobruji.feedback.api.dto.BookmarkToggleRequest;
import com.mobruji.feedback.api.dto.BookmarkToggleResponse;
import com.mobruji.feedback.api.dto.BookmarkWithSongResponse;
import com.mobruji.feedback.application.BookmarkService;
import com.mobruji.feedback.application.BookmarkService.BookmarkPageSlice;
import com.mobruji.feedback.application.ToggleResult;
import com.mobruji.song.domain.Song;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class BookmarkController {

    static final int MAX_PAGE_SIZE = 100;

    private final BookmarkService bookmarkService;

    private final SessionAuthGuard sessionAuthGuard;

    /**
     * 북마크 토글. 같은 (sessionId, songId)로 두 번째 호출하면 bookmarked=false(취소)를 반환한다.
     *
     * <p>인증: §5-2-1 / ADR-0011 — body 의 {@code sessionId} 와 {@code X-Session-Id} 헤더가 일치해야 한다
     * (누락/blank/불일치 모두 401). #258 후속 적용.
     */
    @PostMapping("/api/v1/bookmarks")
    public BookmarkToggleResponse toggle(
            @Valid @RequestBody final BookmarkToggleRequest bookmarkToggleRequest,
            @RequestHeader(value = "X-Session-Id", required = false) final String presentedSessionId) {
        sessionAuthGuard.verify(bookmarkToggleRequest.sessionId(), presentedSessionId);
        final ToggleResult toggleResult = bookmarkService.toggle(
                bookmarkToggleRequest.sessionId(), bookmarkToggleRequest.songId());
        return BookmarkToggleResponse.from(toggleResult);
    }

    /**
     * 세션의 북마크 목록(곡 메타 join + 페이지네이션).
     *
     * <p>spec {@code recommendation-history-and-feedback.md §5-2} 표의 `Page<BookmarkWithSongResponse>` 응답.
     * 인증: §5-2-1 / ADR-0011 — {@code X-Session-Id} 헤더 일치 필수 (누락/blank/불일치 모두 401).
     */
    @GetMapping("/api/v1/sessions/{sessionId}/bookmarks")
    public BookmarkListResponse readBySessionId(
            @PathVariable final String sessionId,
            @RequestParam(value = "page", defaultValue = "0") final int page,
            @RequestParam(value = "size", defaultValue = "20") final int size,
            @RequestHeader(value = "X-Session-Id", required = false) final String presentedSessionId) {
        sessionAuthGuard.verify(sessionId, presentedSessionId);
        validatePagination(page, size);
        final BookmarkPageSlice slice = bookmarkService.readPageBySessionId(sessionId, page, size);
        final List<BookmarkWithSongResponse> responses = slice.bookmarks().stream()
                .map(bookmark -> {
                    final Song song = slice.songsById().get(bookmark.getSongId());
                    return song == null ? null : BookmarkWithSongResponse.from(bookmark, song);
                })
                .filter(Objects::nonNull)
                .toList();
        final long totalCount = slice.totalCount();
        final boolean hasNext = (long) (page + 1) * size < totalCount;
        return new BookmarkListResponse(responses, page, size, totalCount, hasNext);
    }

    private void validatePagination(final int page, final int size) {
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
