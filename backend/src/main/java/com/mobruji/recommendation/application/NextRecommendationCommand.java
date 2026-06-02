package com.mobruji.recommendation.application;

import java.util.List;
import java.util.Objects;

/**
 * "부른 곡 기반 다음곡 추천"(#1486) 유스케이스 입력 커맨드.
 *
 * <p>{@code seedSongIds} 는 사용자가 방금/과거에 부른 곡 ID(최소 1개). {@link SeedSongProfiler} 가
 * 이 곡들로부터 음역대·분위기·BPM 을 도출한다. seed 곡 자체는 결과에서 자동 제외된다(이미 부른 곡).
 *
 * <p>{@code excludeSongIds} 는 seed 외에 추가로 제외할 곡(예: 스와이프에서 패스한 곡). null 이 아닌
 * 빈 리스트로 정규화된 값을 받는다(api.dto 의 {@code excludeSongIdsOrEmpty()} 결과 등).
 */
public record NextRecommendationCommand(
        String sessionId,
        List<Long> seedSongIds,
        List<Long> excludeSongIds
) {

    public NextRecommendationCommand {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(seedSongIds, "seedSongIds must not be null");
        Objects.requireNonNull(excludeSongIds, "excludeSongIds must not be null");
        if (seedSongIds.isEmpty()) {
            throw new IllegalArgumentException("seedSongIds must not be empty");
        }
        seedSongIds = List.copyOf(seedSongIds);
        excludeSongIds = List.copyOf(excludeSongIds);
    }
}
