package com.mobruji.recommendation.api.dto;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.mobruji.recommendation.application.CreateRecommendationCommand;
import com.mobruji.song.domain.Mood;

/**
 * 추천 생성 요청 DTO.
 *
 * <p>spec: {@code docs/features/recommendation-algorithm-v1.md} §3 기능 요구사항
 * — 입력은 {@code VoiceRange}(필수) + {@code mood}(선택) + {@code excludeSongIds}(재추천 시).
 *
 * <p>{@code excludeSongIds}는 사용자가 "이미 들었어요/봤어요"로 결과에서 빼고 싶은 곡 ID 목록.
 * null 허용(JSON에서 필드 자체를 생략 가능). 서비스 레이어에서 null→empty로 정규화한다.
 * 결정성을 위해 본 값은 {@code SeedDeriver}의 입력에도 포함되며,
 * 같은 음역대라도 excludeSongIds가 다르면 jitter seed가 달라져 결과 변주가 발생한다.
 */
public record RecommendationCreateRequest(
        @NotBlank String sessionId,
        @NotNull @Min(12) @Max(119) Integer voiceRangeLow,
        @NotNull @Min(12) @Max(119) Integer voiceRangeHigh,
        Mood mood,
        List<Long> excludeSongIds
) {

    /**
     * null-safe 접근자. 직렬화 결과(null) 또는 client 미입력을 빈 리스트로 정규화한다.
     */
    public List<Long> excludeSongIdsOrEmpty() {
        return excludeSongIds == null ? List.of() : excludeSongIds;
    }

    public CreateRecommendationCommand toCommand() {
        return new CreateRecommendationCommand(
                sessionId, voiceRangeLow, voiceRangeHigh, mood, excludeSongIdsOrEmpty());
    }
}
