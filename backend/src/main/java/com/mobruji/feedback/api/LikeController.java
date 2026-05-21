package com.mobruji.feedback.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.mobruji.feedback.api.dto.LikeResponse;
import com.mobruji.feedback.api.dto.LikeToggleRequest;
import com.mobruji.feedback.api.dto.LikeToggleResponse;
import com.mobruji.feedback.application.LikeService;
import com.mobruji.feedback.application.ToggleResult;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class LikeController {

    private final LikeService likeService;

    /**
     * 좋아요 토글. 같은 (sessionId, songId)로 두 번째 호출하면 liked=false(취소)를 반환한다.
     * spec recommendation-history-and-feedback.md §5-2 — POST는 멱등적 toggle 동작.
     */
    @PostMapping("/api/v1/likes")
    public LikeToggleResponse toggle(@Valid @RequestBody final LikeToggleRequest likeToggleRequest) {
        final ToggleResult toggleResult = likeService.toggle(
                likeToggleRequest.sessionId(), likeToggleRequest.songId());
        return LikeToggleResponse.from(toggleResult);
    }

    @GetMapping("/api/v1/sessions/{sessionId}/likes")
    public List<LikeResponse> readBySessionId(@PathVariable final String sessionId) {
        return likeService.readBySessionId(sessionId).stream().map(LikeResponse::from).toList();
    }
}
