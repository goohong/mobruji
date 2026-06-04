package com.mobruji.recommendation.application;

import java.util.Objects;

import com.mobruji.recommendation.domain.AgeGroup;
import com.mobruji.song.domain.VocalGender;

/**
 * 듀엣·함께 부르기형(P-G) 추천 유스케이스의 입력 커맨드. api.dto 의존을 끊기 위해 application 계층에 둔 입력 모델(ADR 0005 §A-7).
 * 검증 어노테이션은 api.dto에서 수행하고, 커맨드는 정규화된 입력만 담는다.
 *
 * <p>단일 추천이 1인 음역만 받는 데 반해 듀엣은 두 사람의 음역을 받는다 — {@code voiceRangeLow}/{@code voiceRangeHigh}는
 * 1인(요청자) 음역, {@code partnerVoiceRangeLow}/{@code partnerVoiceRangeHigh}는 2인(파트너) 음역(둘 다 필수). 두 음역을 모두
 * 충족하는 곡을 두 음역 교집합 적합도로 재정렬하므로(persona-expansion-social-emotional.md §5-1) 둘 다 필요하다.
 *
 * <p>{@code gender}/{@code partnerGender}(옵션)는 두 사람의 성별 — 파트 분담 라벨("남성 파트/여성 파트")에만 쓰이고 후보 필터로는
 * 쓰지 않는다(듀엣은 두 성별이 섞이므로 단일 성별 필터로 후보를 줄이면 듀엣곡이 배제된다). {@code ageGroup}(옵션)은 두 사람 공통
 * 연령대 대표값 — {@code generationFit} 신호에 반영, null이면 기여 0(하위호환).
 *
 * <p>{@code limit}(옵션)은 노출 곡 수 상한. null이면 단일 추천과 같은 기본 결과 개수({@code RecommendationProperties.resultCount}).
 */
public record DuetRecommendationCommand(
        String sessionId,
        int voiceRangeLow,
        int voiceRangeHigh,
        int partnerVoiceRangeLow,
        int partnerVoiceRangeHigh,
        VocalGender gender,
        VocalGender partnerGender,
        AgeGroup ageGroup,
        Integer limit
) {

    public DuetRecommendationCommand {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (limit != null && limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1: " + limit);
        }
    }
}
