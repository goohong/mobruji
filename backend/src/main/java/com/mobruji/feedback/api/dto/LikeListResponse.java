package com.mobruji.feedback.api.dto;

import java.util.List;

/**
 * 좋아요 리스트 페이지네이션 wrapper.
 *
 * <p>spec {@code recommendation-history-and-feedback.md §5-2} — {@code Page<LikeWithSongResponse>}
 * 의미. Spring Data {@code Page<>} 를 직접 노출하면 직렬화 안정성 이슈(필드명 미고정)가 있어 응답 안정성을 위한 명시적 wrapper 를 둔다
 * ({@code RecommendationHistoryListResponse} 동일 패턴).
 *
 * <p>리스트 변수명은 코드 컨벤션(CLAUDE.md §4) — `responses`.
 */
public record LikeListResponse(
        List<LikeWithSongResponse> responses,
        int page,
        int size,
        long totalCount,
        boolean hasNext
) {
}
