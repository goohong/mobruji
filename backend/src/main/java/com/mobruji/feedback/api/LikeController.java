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
import com.mobruji.feedback.api.dto.LikeListResponse;
import com.mobruji.feedback.api.dto.LikeToggleRequest;
import com.mobruji.feedback.api.dto.LikeToggleResponse;
import com.mobruji.feedback.api.dto.LikeWithSongResponse;
import com.mobruji.feedback.application.LikeService;
import com.mobruji.feedback.application.LikeService.LikePageSlice;
import com.mobruji.feedback.application.ToggleResult;
import com.mobruji.song.domain.Song;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class LikeController {

    /**
     * 페이지네이션 기본/최대 값. spec {@code recommendation-history-and-feedback.md §5-2} — `Page<>` 응답.
     * 최대 size 는 의도치 않은 대용량 응답(메모리/네트워크 부담)을 막기 위한 가드.
     */
    static final int DEFAULT_PAGE_SIZE = 20;

    static final int MAX_PAGE_SIZE = 100;

    private final LikeService likeService;

    private final SessionAuthGuard sessionAuthGuard;

    /**
     * 좋아요 토글. 같은 (sessionId, songId)로 두 번째 호출하면 liked=false(취소)를 반환한다.
     * spec recommendation-history-and-feedback.md §5-2 — POST는 멱등적 toggle 동작.
     *
     * <p>인증: §5-2-1 / ADR-0011 — body 의 {@code sessionId} 와 {@code X-Session-Id} 헤더가 일치해야 한다
     * (누락/blank/불일치 모두 401). #258 후속 적용.
     */
    @PostMapping("/api/v1/likes")
    public LikeToggleResponse toggle(
            @Valid @RequestBody final LikeToggleRequest likeToggleRequest,
            @RequestHeader(value = "X-Session-Id", required = false) final String presentedSessionId) {
        sessionAuthGuard.verify(likeToggleRequest.sessionId(), presentedSessionId);
        final ToggleResult toggleResult = likeService.toggle(
                likeToggleRequest.sessionId(), likeToggleRequest.songId());
        return LikeToggleResponse.from(toggleResult);
    }

    /**
     * 세션의 좋아요 목록(곡 메타 join + 페이지네이션).
     *
     * <p>spec {@code recommendation-history-and-feedback.md §5-2} 표의 `Page<LikeWithSongResponse>` 응답.
     * 인증: §5-2-1 / ADR-0011 — {@code X-Session-Id} 헤더가 path sessionId 와 일치해야 한다 (누락/blank/불일치 모두 401).
     *
     * <p>{@code page}/{@code size} 미지정 시 0/20. {@code size} 는 {@value #MAX_PAGE_SIZE} 로 cap.
     * 응답은 {@link LikeListResponse} wrapper — `responses` 필드명은 코드 컨벤션(CLAUDE.md §4).
     */
    @GetMapping("/api/v1/sessions/{sessionId}/likes")
    public LikeListResponse readBySessionId(
            @PathVariable final String sessionId,
            @RequestParam(value = "page", defaultValue = "0") final int page,
            @RequestParam(value = "size", defaultValue = "20") final int size,
            @RequestHeader(value = "X-Session-Id", required = false) final String presentedSessionId) {
        sessionAuthGuard.verify(sessionId, presentedSessionId);
        validatePagination(page, size);
        final LikePageSlice slice = likeService.readPageBySessionId(sessionId, page, size);
        final List<LikeWithSongResponse> responses = slice.likes().stream()
                .map(like -> {
                    final Song song = slice.songsById().get(like.getSongId());
                    // 곡이 삭제된 경우 (lookup 누락) — null 매핑 후 filter
                    return song == null ? null : LikeWithSongResponse.from(like, song);
                })
                .filter(Objects::nonNull)
                .toList();
        final long totalCount = slice.totalCount();
        final boolean hasNext = (long) (page + 1) * size < totalCount;
        return new LikeListResponse(responses, page, size, totalCount, hasNext);
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
