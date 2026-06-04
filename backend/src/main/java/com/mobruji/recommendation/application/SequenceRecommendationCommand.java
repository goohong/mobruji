package com.mobruji.recommendation.application;

import java.util.Objects;

import com.mobruji.recommendation.domain.AgeGroup;
import com.mobruji.song.domain.VocalGender;

/**
 * 모임 사회자형(P-D) 시퀀스 추천 유스케이스의 입력 커맨드. api.dto 의존을 끊기 위해 application 계층에 둔 입력 모델(ADR 0005 §A-7).
 * 검증 어노테이션은 api.dto에서 수행하고, 커맨드는 정규화된 입력만 담는다.
 *
 * <p>{@code voiceRangeLow}/{@code voiceRangeHigh}는 좌중의 공통·평균 음역 힌트다. 추천 점수 함수의 {@code rangeFit}
 * centeredness 항이 이 음역 중앙에 가까운 곡을 우선하므로, 평균 음역을 넘기는 것만으로 한쪽 극단(매우 높은/낮은 음역)으로 쏠리지 않는
 * 중앙 편향(persona-expansion-social-emotional.md §4 "특정인 비-과편향 가드")이 자연히 적용된다 — 별도 가드 로직 없이 기존 신호로 충족.
 *
 * <p>{@code ageGroup}은 좌중 연령대 분포의 대표값(옵션). 단계별 {@code generationFit} 신호에 반영된다. null이면 기여 0(하위호환).
 * {@code gender}는 성별 필터(옵션). null이면 기여 0.
 *
 * <p>{@code songsPerStage}는 단계별 노출 곡 수(옵션). null이면 단일 추천과 같은 기본 결과 개수({@code RecommendationProperties.resultCount})를
 * 쓴다.
 */
public record SequenceRecommendationCommand(
        String sessionId,
        int voiceRangeLow,
        int voiceRangeHigh,
        AgeGroup ageGroup,
        VocalGender gender,
        Integer songsPerStage
) {

    public SequenceRecommendationCommand {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (songsPerStage != null && songsPerStage < 1) {
            throw new IllegalArgumentException("songsPerStage must be >= 1: " + songsPerStage);
        }
    }
}
