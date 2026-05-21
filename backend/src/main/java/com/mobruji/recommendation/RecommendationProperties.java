package com.mobruji.recommendation;

import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
 */
@ConfigurationProperties(prefix = "recommendation")
public record RecommendationProperties(
        Weights weights,
        Diversity diversity,
        int resultCount,
        double jitterMagnitude,
        SeedStrategy seedStrategy
) {

    public RecommendationProperties {
        Objects.requireNonNull(weights, "weights must not be null");
        Objects.requireNonNull(diversity, "diversity must not be null");
        Objects.requireNonNull(seedStrategy, "seedStrategy must not be null");
        if (resultCount <= 0) {
            throw new IllegalArgumentException("resultCount must be > 0: " + resultCount);
        }
        if (jitterMagnitude < 0) {
            throw new IllegalArgumentException("jitterMagnitude must be >= 0: " + jitterMagnitude);
        }
    }

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
            double voiceFit,
            double genre,
            double mood,
            double popularity
    ) {

        public Weights {
            if (voiceFit < 0 || genre < 0 || mood < 0 || popularity < 0) {
                throw new IllegalArgumentException(
                        "weights must be >= 0 (voiceFit=" + voiceFit + ", genre=" + genre
                                + ", mood=" + mood + ", popularity=" + popularity + ")");
            }
        }
    }

    public record Diversity(
            int maxSameArtist,
            int maxSameGenre
    ) {

        public Diversity {
            if (maxSameArtist <= 0) {
                throw new IllegalArgumentException("maxSameArtist must be > 0: " + maxSameArtist);
            }
            if (maxSameGenre <= 0) {
                throw new IllegalArgumentException("maxSameGenre must be > 0: " + maxSameGenre);
            }
        }
    }
}
