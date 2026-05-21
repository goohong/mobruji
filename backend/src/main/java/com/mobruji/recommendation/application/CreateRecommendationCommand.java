package com.mobruji.recommendation.application;

import java.util.List;
import java.util.Objects;

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
 */
public record CreateRecommendationCommand(
        String sessionId,
        int voiceRangeLow,
        int voiceRangeHigh,
        Mood mood,
        Integer preferredBpm,
        List<Long> excludeSongIds
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
