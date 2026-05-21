package com.mobruji.recommendation.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.mobruji.auth.SessionAuthGuard;
import com.mobruji.recommendation.api.dto.RecommendationHistoryListResponse;
import com.mobruji.recommendation.api.dto.RecommendationHistoryResponse;
import com.mobruji.recommendation.application.RecommendationService;
import com.mobruji.recommendation.application.RecommendationService.RecommendationHistorySnapshot;

import lombok.RequiredArgsConstructor;

/**
 * 세션별 추천 히스토리(요청 + 결과) 조회 엔드포인트.
 *
 * <p>spec: docs/features/recommendation-history-and-feedback.md §5-2 (PR C — closes #236).
 *
 * <p>경로 명명: spec §5-2 표의 `/api/v1/sessions/{sessionId}/recommendations` 와 동일 의미지만,
 * voice-range-progress 의 `/voice-range-history` 와 통일된 동사 명사형(`recommendation-history`)을
 * 사용한다. 두 도메인 모두 "history" 단어가 fe URL 과 의미적으로 일치.
 *
 * <p>기존 `RecommendationController`(`/api/v1/recommendations`) 와 매핑 프리픽스가 달라 별도 컨트롤러로 분리.
 *
 * <p>인증: rev 16(#238) — {@code X-Session-Id} 헤더로 호출자 sessionId 를 받아 path 와 일치할 때만
 * 통과한다 ({@link SessionAuthGuard}). 누락/불일치 → 401.
 */
@RestController
@RequiredArgsConstructor
public class RecommendationHistoryController {

    private final RecommendationService recommendationService;
    private final SessionAuthGuard sessionAuthGuard;

    @GetMapping("/api/v1/sessions/{sessionId}/recommendation-history")
    public RecommendationHistoryListResponse readHistory(
            @PathVariable final String sessionId,
            @RequestHeader(value = "X-Session-Id", required = false) final String presentedSessionId) {
        sessionAuthGuard.verify(sessionId, presentedSessionId);
        return new RecommendationHistoryListResponse(
                recommendationService.readHistoryBySessionId(sessionId).stream()
                        .map(this::toHistoryResponse)
                        .toList());
    }

    private RecommendationHistoryResponse toHistoryResponse(final RecommendationHistorySnapshot snapshot) {
        return RecommendationHistoryResponse.from(snapshot.request(), snapshot.result());
    }
}
