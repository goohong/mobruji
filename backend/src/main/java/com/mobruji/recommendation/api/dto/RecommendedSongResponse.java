package com.mobruji.recommendation.api.dto;

import com.mobruji.recommendation.domain.ScoredRecommendation;
import com.mobruji.song.api.dto.SongResponse;
import com.mobruji.song.domain.Difficulty;

/**
 * 추천 곡 1건 응답 DTO.
 *
 * <p>{@code voiceFit}(0~1) / {@code voiceFitReason}은 "왜 이 곡?"(설명 가능성, #1484)을 전면 노출하는 필드로,
 * breakdown 의 {@code rangeFit} 신호를 곡별 음역 적합도 점수와 짧은 한국어 사유로 풀어 준다.
 * {@code moodFit}(0~1) / {@code moodFitReason}은 같은 패턴으로 {@code moodMatch} 분위기 연속 유사도(#1485)를 노출한다.
 * breakdown 이 없는 과거 추천 재조회 경로에서는 {@code breakdown}과 함께 모두 {@code null}.
 *
 * <p>{@code practiceDifficulty} / {@code practiceDifficultyReason}은 연습형 페르소나(P-A, #1494)용으로 곡 자체의
 * 가창 난이도·최고음을 노출한다. 곡 속성에서 파생하므로 재조회 경로에서도 채워지며, 음역 미보유 곡은 graceful 하게
 * 난이도 {@code null} + "정보 없음" 사유로 노출된다.
 */
public record RecommendedSongResponse(
        SongResponse song,
        double score,
        String matchReason,
        Double voiceFit,
        String voiceFitReason,
        Double moodFit,
        String moodFitReason,
        Difficulty practiceDifficulty,
        String practiceDifficultyReason,
        int rankPosition,
        ScoreBreakdownResponse breakdown
) {

    public static RecommendedSongResponse from(final ScoredRecommendation scoredRecommendation) {
        return new RecommendedSongResponse(
                SongResponse.from(scoredRecommendation.song()),
                scoredRecommendation.score(),
                scoredRecommendation.matchReason(),
                scoredRecommendation.voiceFit(),
                scoredRecommendation.voiceFitReason(),
                scoredRecommendation.moodFit(),
                scoredRecommendation.moodFitReason(),
                scoredRecommendation.practiceDifficulty(),
                scoredRecommendation.practiceDifficultyReason(),
                scoredRecommendation.rankPosition(),
                ScoreBreakdownResponse.from(scoredRecommendation.breakdown()));
    }
}
