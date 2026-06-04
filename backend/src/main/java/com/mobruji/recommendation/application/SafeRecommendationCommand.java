package com.mobruji.recommendation.application;

import java.util.Objects;

import com.mobruji.recommendation.domain.AgeGroup;
import com.mobruji.song.domain.VocalGender;

/**
 * 안전곡형(P-E) 추천 유스케이스의 입력 커맨드. api.dto 의존을 끊기 위해 application 계층에 둔 입력 모델(ADR 0005 §A-7).
 * 검증 어노테이션은 api.dto에서 수행하고, 커맨드는 정규화된 입력만 담는다.
 *
 * <p>{@code voiceRangeLow}/{@code voiceRangeHigh}는 사용자 음역대(필수, 추천 구조상). 안전곡 프리셋은 좁은 음역 여유를
 * 우선하므로 음역 적합도({@code rangeFit})를 그대로 재사용한다(persona-expansion-social-emotional.md §5-1).
 *
 * <p>{@code ageGroup}(옵션)은 연령대 대표값 — {@code generationFit} 신호에 반영, null이면 기여 0(하위호환).
 * {@code gender}(옵션)는 성별 필터 — {@code genderFit} 신호에 반영, null이면 기여 0.
 *
 * <p>{@code limit}(옵션)은 노출 곡 수 상한. null이면 단일 추천과 같은 기본 결과 개수({@code RecommendationProperties.resultCount}).
 */
public record SafeRecommendationCommand(
        String sessionId,
        int voiceRangeLow,
        int voiceRangeHigh,
        AgeGroup ageGroup,
        VocalGender gender,
        Integer limit
) {

    public SafeRecommendationCommand {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (limit != null && limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1: " + limit);
        }
    }
}
