package com.mobruji.recommendation.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mobruji.recommendation.api.dto.FeedbackRecordRequest;
import com.mobruji.recommendation.api.dto.FeedbackRecordResponse;
import com.mobruji.recommendation.api.dto.SessionFeedbackListResponse;
import com.mobruji.recommendation.api.dto.SessionFeedbackResponse;
import com.mobruji.recommendation.application.SessionFeedbackService;
import com.mobruji.recommendation.application.SessionFeedbackService.SessionFeedbackPageSlice;
import com.mobruji.recommendation.domain.SessionFeedback;
import com.mobruji.user.application.SessionAuthGuard;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 스와이프 세션 반응 기록·조회 API(#1545 recommendation-feedback-loop.md §5-2).
 *
 * <p>두 endpoint 모두 session-bound(ADR-0011 / {@link SessionAuthGuard}) — path/body 의 {@code sessionId}
 * 가 {@code X-Session-Id} 헤더와 일치해야 한다(누락/blank/불일치 모두 401). 기록은 upsert(최신 reaction 으로
 * 덮어쓰기).
 */
@RestController
@RequiredArgsConstructor
public class SessionFeedbackController {

    private static final int MAX_PAGE_SIZE = 100;

    private final SessionFeedbackService sessionFeedbackService;

    private final SessionAuthGuard sessionAuthGuard;

    /**
     * 스와이프 반응 기록(upsert). 같은 곡으로 다시 호출하면 최신 reaction 으로 덮어쓴다.
     * 존재하지 않는 songId 면 404.
     */
    @PostMapping("/api/v1/sessions/{sessionId}/feedback")
    public FeedbackRecordResponse record(
            @PathVariable final String sessionId,
            @Valid @RequestBody final FeedbackRecordRequest feedbackRecordRequest,
            @RequestHeader(value = "X-Session-Id", required = false) final String presentedSessionId) {
        sessionAuthGuard.verify(sessionId, presentedSessionId);
        if (!sessionId.equals(feedbackRecordRequest.sessionId())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "session id mismatch");
        }
        final SessionFeedback saved = sessionFeedbackService.record(
                sessionId, feedbackRecordRequest.songId(), feedbackRecordRequest.reaction());
        return FeedbackRecordResponse.from(saved);
    }

    /**
     * 세션 반응 목록(최신순 + 페이지네이션). {@code page}/{@code size} 미지정 시 0/20.
     * {@code size} 는 {@value #MAX_PAGE_SIZE} 로 cap.
     */
    @GetMapping("/api/v1/sessions/{sessionId}/feedback")
    public SessionFeedbackListResponse readBySessionId(
            @PathVariable final String sessionId,
            @RequestParam(value = "page", defaultValue = "0") final int page,
            @RequestParam(value = "size", defaultValue = "20") final int size,
            @RequestHeader(value = "X-Session-Id", required = false) final String presentedSessionId) {
        sessionAuthGuard.verify(sessionId, presentedSessionId);
        validatePagination(page, size);
        final SessionFeedbackPageSlice slice = sessionFeedbackService.readPageBySessionId(sessionId, page, size);
        final List<SessionFeedbackResponse> responses = slice.feedbacks().stream()
                .map(SessionFeedbackResponse::from)
                .toList();
        final long totalCount = slice.totalCount();
        final boolean hasNext = (long) (page + 1) * size < totalCount;
        return new SessionFeedbackListResponse(responses, page, size, totalCount, hasNext);
    }

    private static void validatePagination(final int page, final int size) {
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be >= 0");
        }
        if (size < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size must be >= 1");
        }
        if (size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size must be <= " + MAX_PAGE_SIZE);
        }
    }
}
