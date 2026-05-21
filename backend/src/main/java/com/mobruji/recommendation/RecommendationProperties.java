package com.mobruji.recommendation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 추천 v1 점수 가중치 / 다양성 후처리 / 결과 개수 설정.
 *
 * <p>spec: {@code docs/features/recommendation-algorithm-v1.md} §9 결정 로그.
 * v1 시점에 실제 영향: voiceFit + mood + jitter. genre/popularity 가중치는 보존이 목적이며 신호값은 아래 주석 참고.
 *
 * <ul>
 * <li>{@code voiceFit}: 곡 키 음역 ↔ 사용자 음역 매칭 (0~1)</li>
 * <li>{@code genre}: 현재 비활성 (request에 genre 입력 필드 없음 → 신호값 0)</li>
 * <li>{@code mood}: 분위기 일치 시 1, 아니면 0</li>
 * <li>{@code popularity}: 시드 데이터에 popularity 필드 부재 → 신호값 1.0 고정. 가중치는 가산점으로만 작용.</li>
 * </ul>
 *
 * <p>검증 정책: 모든 필드 검증을 Bean Validation으로 통일한다 (PR #54). 바인딩 시점에 fail-fast.
 * spec §6 운영 원칙: 도메인 객체는 {@code Objects.requireNonNull} 유지, properties는 어노테이션 일관화.
 */
@Validated
@ConfigurationProperties(prefix = "recommendation")
public record RecommendationProperties(
        @NotNull @Valid Weights weights,
        @NotNull @Valid Diversity diversity,
        @Min(1) int resultCount,
        @DecimalMin("0.0") double jitterMagnitude,
        @NotNull SeedStrategy seedStrategy
) {

    /**
     * jitter용 {@link java.util.Random} 시드 전략.
     *
     * <ul>
     * <li>{@link #DERIVED}: 요청 파라미터 해시에서 seed 도출. 같은 입력 → 같은 결과. spec §3 비기능 기본값.</li>
     * <li>{@link #RANDOM}: seed 없는 {@code new Random()}. 결정성을 깨고 매 호출마다 변주가 필요한
     * 디버깅·관찰용. 운영 기본값으로는 쓰지 않는다.</li>
     * </ul>
     */
    public enum SeedStrategy {
        DERIVED,
        RANDOM
    }

    public record Weights(
            @DecimalMin("0.0") double voiceFit,
            @DecimalMin("0.0") double genre,
            @DecimalMin("0.0") double mood,
            @DecimalMin("0.0") double popularity
    ) {
    }

    public record Diversity(
            @Min(1) int maxSameArtist,
            @Min(1) int maxSameGenre
    ) {
    }
}
