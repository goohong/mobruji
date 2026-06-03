package com.mobruji.recommendation.application;

import java.util.List;
import java.util.Objects;

import com.mobruji.recommendation.domain.AgeGroup;
import com.mobruji.song.domain.Mood;

/**
 * 추천 생성 유스케이스의 입력 커맨드. api.dto 의존을 끊기 위해 application 계층에 둔 입력 모델
 * (ADR 0005 §A-7). 검증 어노테이션은 api.dto에서 수행하고, 커맨드는 정규화된 입력만 담는다.
 *
 * <p>{@code excludeSongIds}는 null이 아닌 빈 리스트로 정규화된 값을 받는다 (api.dto의
 * {@code excludeSongIdsOrEmpty()} 호출 결과 등).
 *
 * <p>{@code preferredBpm}은 v2(#218)에서 도입된 사용자 선호 BPM 입력(옵션, nullable).
 * null이면 mood 기반 default BPM으로 폴백한다 ({@code RecommendationProperties.Tempo.moodDefaultBpm}).
 *
 * <p>{@code ageGroup}은 #1487에서 도입된 연령대 입력(옵션, nullable). null이면 generationFit 신호가 0 이 되어
 * 랭킹에 영향이 없다.
 *
 * <p>{@code excludeSessionHistory}는 #1549에서 도입된 세션 단위 자동 중복 회피 플래그. {@code true}면 서비스가
 * 같은 {@code sessionId}의 이전 추천 결과 곡과 이전에 제외/부른 곡을 {@code excludeSongIds}에 자동 누적 병합해
 * 반복 추천을 방지한다. {@code false}(기본)면 기존 동작 그대로 — 클라이언트가 넘긴 {@code excludeSongIds}만 적용.
 */
public record CreateRecommendationCommand(
        String sessionId,
        int voiceRangeLow,
        int voiceRangeHigh,
        Mood mood,
        Integer preferredBpm,
        AgeGroup ageGroup,
        List<Long> excludeSongIds,
        boolean excludeSessionHistory
) {

    public CreateRecommendationCommand {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(excludeSongIds, "excludeSongIds must not be null");
        excludeSongIds = List.copyOf(excludeSongIds);
        if (preferredBpm != null && (preferredBpm < 30 || preferredBpm > 300)) {
            throw new IllegalArgumentException(
                    "preferredBpm out of plausible range [30, 300]: " + preferredBpm);
        }
    }
}
