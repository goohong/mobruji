package com.mobruji.feedback.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.mobruji.feedback.api.dto.BookmarkResponse;
import com.mobruji.feedback.api.dto.BookmarkToggleRequest;
import com.mobruji.feedback.api.dto.BookmarkToggleResponse;
import com.mobruji.feedback.application.BookmarkService;
import com.mobruji.feedback.application.ToggleResult;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class BookmarkController {

    private final BookmarkService bookmarkService;

    @PostMapping("/api/v1/bookmarks")
    public BookmarkToggleResponse toggle(@Valid @RequestBody final BookmarkToggleRequest bookmarkToggleRequest) {
        final ToggleResult toggleResult = bookmarkService.toggle(
                bookmarkToggleRequest.sessionId(), bookmarkToggleRequest.songId());
        return BookmarkToggleResponse.from(toggleResult);
    }

    @GetMapping("/api/v1/sessions/{sessionId}/bookmarks")
    public List<BookmarkResponse> readBySessionId(@PathVariable final String sessionId) {
        return bookmarkService.readBySessionId(sessionId).stream().map(BookmarkResponse::from).toList();
    }
}
