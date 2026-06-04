package com.mobruji.recommendation.domain;

import java.util.List;
import java.util.Objects;

import com.mobruji.song.domain.Mood;

/**
 * 모임 사회자형(P-D) 시퀀스 추천 1건의 최종 결과 — 페르소나 식별자 + 자리 단계별 추천 묶음(워밍업/고조/마무리).
 * application 계층이 {@code api.dto}에 의존하지 않도록 domain 레이어에 두는 결과 컨테이너 (ADR 0005 §A-7).
 *
 * <p>각 {@link StageRecommendation}은 한 단계의 분위기({@link Mood})로 산출한 단일 추천 결과({@link RecommendationResult})를
 * 그대로 감싼다 — 단계마다 별도 추천 요청이 영속되어 고유 {@code requestId}를 가지며, 단계별 곡 피드백·재조회를 단일 추천과 같은
 * 경로로 처리할 수 있다.
 */
public record SequenceRecommendationResult(
        RecommendationPersona persona,
        List<StageRecommendation> stages
) {

    public SequenceRecommendationResult {
        Objects.requireNonNull(persona, "persona must not be null");
        Objects.requireNonNull(stages, "stages must not be null");
        stages = List.copyOf(stages);
    }

    /**
     * 시퀀스 한 단계의 추천 — 단계 식별자 + 그 단계의 분위기 + 단계 설명 + 단계별 추천 결과.
     */
    public record StageRecommendation(
            SequenceStage stage,
            Mood mood,
            String stageReason,
            RecommendationResult result
    ) {

        public StageRecommendation {
            Objects.requireNonNull(stage, "stage must not be null");
            Objects.requireNonNull(mood, "mood must not be null");
            Objects.requireNonNull(stageReason, "stageReason must not be null");
            Objects.requireNonNull(result, "result must not be null");
        }
    }
}
