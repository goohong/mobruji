package com.mobruji.recommendation.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.mobruji.recommendation.application.SequenceRecommendationCommand;
import com.mobruji.recommendation.domain.AgeGroup;
import com.mobruji.song.domain.VocalGender;
import com.mobruji.user.domain.AnonymousSession;
import com.mobruji.user.domain.SessionIdPatterns;

/**
 * 모임 사회자형(P-D) 시퀀스 추천 생성 요청 DTO (persona-expansion-social-emotional.md §5-4).
 *
 * <p>{@code voiceRangeLow}/{@code voiceRangeHigh}는 좌중의 공통·평균 음역 힌트(필수). 단일 추천과 같은 음역 적합도 산식을
 * 재사용하며, 평균 음역 중앙 편향으로 한쪽 극단에 쏠리지 않게 한다(§4 "특정인 비-과편향 가드").
 *
 * <p>{@code ageGroup}(옵션)은 좌중 연령대 분포 대표값, {@code gender}(옵션)는 성별 필터. 둘 다 미입력이면 해당 신호 기여 0(하위호환).
 * {@code songsPerStage}(옵션)는 단계별 노출 곡 수 상한. 미입력이면 단일 추천과 같은 기본 결과 개수.
 *
 * <p>{@code sessionId}는 client 발급 UUIDv4(ADR-0011) — {@link SessionIdPatterns#UUID_V4} 형식 강제.
 */
public record SequenceRecommendationCreateRequest(
        @NotBlank @Size(max = AnonymousSession.SESSION_ID_MAX_LENGTH) @Pattern(
                regexp = SessionIdPatterns.UUID_V4, message = SessionIdPatterns.UUID_V4_MESSAGE
        ) String sessionId,
        @NotNull @Min(12) @Max(119) Integer voiceRangeLow,
        @NotNull @Min(12) @Max(119) Integer voiceRangeHigh,
        AgeGroup ageGroup,
        VocalGender gender,
        @Min(1) @Max(50) Integer songsPerStage
) {

    public SequenceRecommendationCommand toCommand() {
        return new SequenceRecommendationCommand(
                sessionId, voiceRangeLow, voiceRangeHigh, ageGroup, gender, songsPerStage);
    }
}
