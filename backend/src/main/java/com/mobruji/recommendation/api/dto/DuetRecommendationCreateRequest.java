package com.mobruji.recommendation.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.mobruji.recommendation.application.DuetRecommendationCommand;
import com.mobruji.recommendation.domain.AgeGroup;
import com.mobruji.song.domain.VocalGender;
import com.mobruji.user.domain.AnonymousSession;
import com.mobruji.user.domain.SessionIdPatterns;

/**
 * 듀엣·함께 부르기형(P-G) 추천 생성 요청 DTO (persona-expansion-social-emotional.md §5-4).
 *
 * <p>단일 추천이 1인 음역만 받는 데 반해 듀엣은 두 사람 음역을 받는다 — {@code voiceRangeLow}/{@code voiceRangeHigh}는 1인(요청자),
 * {@code partnerVoiceRangeLow}/{@code partnerVoiceRangeHigh}는 2인(파트너) 음역(둘 다 필수). 두 음역을 모두 충족하는 곡을 두 음역
 * 교집합 적합도로 재정렬한다.
 *
 * <p>{@code gender}/{@code partnerGender}(옵션)는 두 사람 성별 — 파트 분담 라벨에만 쓰이고 후보 필터로는 쓰지 않는다(듀엣은 두 성별이
 * 섞이므로). {@code ageGroup}(옵션)은 두 사람 공통 연령대, {@code limit}(옵션)은 노출 곡 수 상한 — 미입력이면 기본 결과 개수.
 *
 * <p>{@code sessionId}는 client 발급 UUIDv4(ADR-0011) — {@link SessionIdPatterns#UUID_V4} 형식 강제(단일 추천과 같은 검증 일관성).
 */
public record DuetRecommendationCreateRequest(
        @NotBlank @Size(max = AnonymousSession.SESSION_ID_MAX_LENGTH) @Pattern(
                regexp = SessionIdPatterns.UUID_V4, message = SessionIdPatterns.UUID_V4_MESSAGE
        ) String sessionId,
        @NotNull @Min(12) @Max(119) Integer voiceRangeLow,
        @NotNull @Min(12) @Max(119) Integer voiceRangeHigh,
        @NotNull @Min(12) @Max(119) Integer partnerVoiceRangeLow,
        @NotNull @Min(12) @Max(119) Integer partnerVoiceRangeHigh,
        VocalGender gender,
        VocalGender partnerGender,
        AgeGroup ageGroup,
        @Min(1) @Max(50) Integer limit
) {

    public DuetRecommendationCommand toCommand() {
        return new DuetRecommendationCommand(
                sessionId,
                voiceRangeLow,
                voiceRangeHigh,
                partnerVoiceRangeLow,
                partnerVoiceRangeHigh,
                gender,
                partnerGender,
                ageGroup,
                limit);
    }
}
