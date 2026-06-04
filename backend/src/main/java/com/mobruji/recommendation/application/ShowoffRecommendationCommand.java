package com.mobruji.recommendation.application;

import java.util.Objects;

import com.mobruji.recommendation.domain.AgeGroup;
import com.mobruji.song.domain.VocalGender;

/**
 * 과시·킬링파트형(P-F) 추천 유스케이스의 입력 커맨드. api.dto 의존을 끊기 위해 application 계층에 둔 입력 모델(ADR 0005 §A-7).
 * 검증 어노테이션은 api.dto에서 수행하고, 커맨드는 정규화된 입력만 담는다.
 *
 * <p>{@code voiceRangeLow}/{@code voiceRangeHigh}는 사용자 음역대(필수, 추천 구조상). 과시 프리셋은 사용자 최고음 근접도를
 * 우선하므로 {@code voiceRangeHigh}(음역 천장)를 킬링파트 근접 재정렬 기준으로 재사용한다
 * (persona-expansion-social-emotional.md §5-1).
 *
 * <p>{@code ageGroup}(옵션)은 연령대 대표값 — {@code generationFit} 신호에 반영, null이면 기여 0(하위호환).
 * {@code gender}(옵션)는 성별 필터 — {@code genderFit} 신호에 반영, null이면 기여 0.
 *
 * <p>{@code limit}(옵션)은 노출 곡 수 상한. null이면 단일 추천과 같은 기본 결과 개수({@code RecommendationProperties.resultCount}).
 */
public record ShowoffRecommendationCommand(
        String sessionId,
        int voiceRangeLow,
        int voiceRangeHigh,
        AgeGroup ageGroup,
        VocalGender gender,
        Integer limit
) {

    public ShowoffRecommendationCommand {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (limit != null && limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1: " + limit);
        }
    }
}
