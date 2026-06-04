package com.mobruji.recommendation.api.dto;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.mobruji.recommendation.application.CreateRecommendationCommand;
import com.mobruji.recommendation.domain.AgeGroup;
import com.mobruji.song.domain.Mood;
import com.mobruji.song.domain.VocalGender;
import com.mobruji.user.domain.AnonymousSession;
import com.mobruji.user.domain.SessionIdPatterns;

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
 *
 * <p>{@code preferredBpm}은 v2(#218)에서 추가된 사용자 선호 BPM 입력(옵션). null이면 mood 기반 default BPM 적용.
 * 결정성 보장을 위해 {@code SeedDeriver}의 입력에도 포함된다.
 *
 * <p>{@code ageGroup}은 #1487에서 추가된 연령대 입력(옵션). 해당 세대의 대표 시기 곡(발매연도)에 가중을 준다.
 * null이면 generationFit 신호가 0 이 되어 랭킹에 영향이 없다(하위호환). 결정성 seed 입력에도 포함된다.
 *
 * <p>{@code gender}는 #1767에서 추가된 성별 필터 입력(옵션, MALE/FEMALE). 고른 성별의 곡에 가중을 준다(배타 제외 아님).
 * null이면 genderFit 신호가 0 이 되어 랭킹에 영향이 없다(하위호환). 결정성 seed 입력에도 포함된다.
 *
 * <p>{@code sessionId} 는 client 가 발급한 UUIDv4 (ADR-0011).
 * {@link SessionIdPatterns#UUID_V4} 형식 강제 — {@code SessionRotateRequest} /
 * {@code VoiceRangeCreateRequest} 와 동일한 검증 일관성 유지 (#948 후속).
 *
 * <p>{@code excludeSessionHistory}(#1549)는 세션 단위 자동 중복 회피 플래그. {@code true}면 서버가 같은
 * {@code sessionId}의 이전 추천 결과 곡과 이전 제외 곡을 자동 누적 제외한다. null 허용(미입력 = {@code false}).
 */
public record RecommendationCreateRequest(
        @NotBlank @Size(max = AnonymousSession.SESSION_ID_MAX_LENGTH) @Pattern(
                regexp = SessionIdPatterns.UUID_V4, message = SessionIdPatterns.UUID_V4_MESSAGE
        ) String sessionId,
        @NotNull @Min(12) @Max(119) Integer voiceRangeLow,
        @NotNull @Min(12) @Max(119) Integer voiceRangeHigh,
        Mood mood,
        @Min(30) @Max(300) Integer preferredBpm,
        AgeGroup ageGroup,
        VocalGender gender,
        List<Long> excludeSongIds,
        Boolean excludeSessionHistory
) {

    /**
     * null-safe 접근자. 직렬화 결과(null) 또는 client 미입력을 빈 리스트로 정규화한다.
     */
    public List<Long> excludeSongIdsOrEmpty() {
        return excludeSongIds == null ? List.of() : excludeSongIds;
    }

    /**
     * null-safe 접근자. 미입력(null)은 {@code false}로 정규화 — 기본은 기존 동작(세션 누적 제외 비활성).
     */
    public boolean excludeSessionHistoryOrFalse() {
        return excludeSessionHistory != null && excludeSessionHistory;
    }

    public CreateRecommendationCommand toCommand() {
        return new CreateRecommendationCommand(
                sessionId, voiceRangeLow, voiceRangeHigh, mood, preferredBpm, ageGroup, gender,
                excludeSongIdsOrEmpty(), excludeSessionHistoryOrFalse());
    }
}
