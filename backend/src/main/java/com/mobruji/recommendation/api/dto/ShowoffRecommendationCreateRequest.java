package com.mobruji.recommendation.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.mobruji.recommendation.application.ShowoffRecommendationCommand;
import com.mobruji.recommendation.domain.AgeGroup;
import com.mobruji.song.domain.VocalGender;
import com.mobruji.user.domain.AnonymousSession;
import com.mobruji.user.domain.SessionIdPatterns;

/**
 * 과시·킬링파트형(P-F) 추천 생성 요청 DTO (persona-expansion-social-emotional.md §5-4).
 *
 * <p>{@code voiceRangeLow}/{@code voiceRangeHigh}는 사용자 음역대(필수). 단일 추천과 같은 음역 적합도 산식을 재사용하며,
 * {@code voiceRangeHigh}(음역 천장)는 킬링파트 근접 재정렬 기준으로도 쓰인다.
 *
 * <p>{@code ageGroup}(옵션)은 연령대 대표값, {@code gender}(옵션)는 성별 필터. 둘 다 미입력이면 해당 신호 기여 0(하위호환).
 * {@code limit}(옵션)은 노출 곡 수 상한. 미입력이면 단일 추천과 같은 기본 결과 개수.
 *
 * <p>{@code sessionId}는 client 발급 UUIDv4(ADR-0011) — {@link SessionIdPatterns#UUID_V4} 형식 강제(단일 추천과 같은 검증 일관성).
 */
public record ShowoffRecommendationCreateRequest(
        @NotBlank @Size(max = AnonymousSession.SESSION_ID_MAX_LENGTH) @Pattern(
                regexp = SessionIdPatterns.UUID_V4, message = SessionIdPatterns.UUID_V4_MESSAGE
        ) String sessionId,
        @NotNull @Min(12) @Max(119) Integer voiceRangeLow,
        @NotNull @Min(12) @Max(119) Integer voiceRangeHigh,
        AgeGroup ageGroup,
        VocalGender gender,
        @Min(1) @Max(50) Integer limit
) {

    public ShowoffRecommendationCommand toCommand() {
        return new ShowoffRecommendationCommand(sessionId, voiceRangeLow, voiceRangeHigh, ageGroup, gender, limit);
    }
}
