package com.mobruji.recommendation.api.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.mobruji.recommendation.application.NextRecommendationCommand;
import com.mobruji.user.domain.AnonymousSession;
import com.mobruji.user.domain.SessionIdPatterns;

/**
 * "부른 곡 기반 다음곡 추천"(#1486) 요청 DTO.
 *
 * <p>이슈 #1486 (roadmap {@code docs/roadmap/overnight-2026-06-02.md} P-B) — 사용자가 방금/과거에
 * 부른 곡 {@code seedSongIds} 를 입력하면 서버가 그 곡들의 음역대·분위기·BPM 을 도출해 이어 부르기 좋은
 * 다음 곡을 추천한다. 쇼츠식 스와이프 선곡(#1489)의 백엔드 토대.
 *
 * <p>{@code seedSongIds} 는 최소 1개(필수). seed 곡 자체는 결과에서 자동 제외된다.
 * {@code excludeSongIds} 는 seed 외 추가 제외(스와이프 패스 등), 미입력 시 빈 리스트로 정규화.
 *
 * <p>{@code sessionId} 는 client 가 발급한 UUIDv4 (ADR-0011). {@code RecommendationCreateRequest}
 * 와 동일한 {@link SessionIdPatterns#UUID_V4} 검증을 강제한다.
 */
public record NextRecommendationRequest(
        @NotBlank @Size(max = AnonymousSession.SESSION_ID_MAX_LENGTH) @Pattern(
                regexp = SessionIdPatterns.UUID_V4, message = SessionIdPatterns.UUID_V4_MESSAGE
        ) String sessionId,
        @NotEmpty List<Long> seedSongIds,
        List<Long> excludeSongIds
) {

    /**
     * null-safe 접근자. 직렬화 결과(null) 또는 client 미입력을 빈 리스트로 정규화한다.
     */
    public List<Long> excludeSongIdsOrEmpty() {
        return excludeSongIds == null ? List.of() : excludeSongIds;
    }

    public NextRecommendationCommand toCommand() {
        return new NextRecommendationCommand(sessionId, seedSongIds, excludeSongIdsOrEmpty());
    }
}
