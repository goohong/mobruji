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
 *
 * <p>{@code excludeSessionHistory} 는 #1549 세션 단위 자동 중복 회피 플래그. {@code true}면 seed/명시 제외에
 * 더해 같은 세션의 이전 추천 결과 곡까지 자동 누적 제외한다. {@code create} 파이프라인으로 전파된다.
 *
 * <p>{@code useSessionFeedback} 는 #1545 스와이프 세션 반응 결합 플래그. {@code true}(기본)면 서버에 저장된
 * 세션 {@code LIKE} 곡을 부른곡 시드와 함께 선호 집합으로, {@code PASS} 곡을 회피/제외 집합으로 합쳐 추천을
 * 재정렬한다. 반응이 0건이면 기여 0(콜드스타트 — 기존 결과 하위호환).
 */
public record NextRecommendationCommand(
        String sessionId,
        List<Long> seedSongIds,
        List<Long> excludeSongIds,
        boolean excludeSessionHistory,
        boolean useSessionFeedback
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
