package com.mobruji.recommendation.application;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import com.mobruji.song.domain.Mood;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 추천 v1/v2 점수 가중치 / 다양성 후처리 / 결과 개수 / tempo 설정.
 *
 * <p>spec: {@code docs/features/recommendation-algorithm-v1.md} §9 결정 로그 (v2 라인).
 * v1 시점에 실제 영향: voiceFit + mood + jitter. v2(#218) 활성: keyMatch는 신호로만, tempoMatch 신규 가중.
 * genre/popularity 가중치는 보존이 목적이며 신호값은 아래 주석 참고.
 *
 * <ul>
 * <li>{@code voiceFit}: 곡 키 음역 ↔ 사용자 음역 매칭 (0~1)</li>
 * <li>{@code genre}: 현재 비활성 (request에 genre 입력 필드 없음 → 신호값 0)</li>
 * <li>{@code mood}: 분위기 일치 시 1, 아니면 0</li>
 * <li>{@code popularity}: 시드 데이터에 popularity 필드 부재 → 신호값 1.0 고정. 가중치는 가산점으로만 작용.</li>
 * <li>{@code tempoMatch}: v2 신규. 곡 BPM ↔ 사용자 선호 BPM(또는 mood→BPM default) 거리 기반 [0,1].</li>
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
        @NotNull @Valid Tempo tempo,
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
            @DecimalMin("0.0") double popularity,
            @DecimalMin("0.0") double tempoMatch
    ) {
    }

    public record Diversity(
            @Min(1) int maxSameArtist,
            @Min(1) int maxSameGenre
    ) {
    }

    /**
     * tempoMatch 신호 설정.
     *
     * <ul>
     * <li>{@code distanceTolerance}: |songBpm - preferredBpm| 가 이 값 이상이면 score=0,
     * 그 미만이면 {@code 1.0 - distance/tolerance} 로 선형 감쇠. 기본 40 (BPM).</li>
     * <li>{@code moodDefaultBpm}: 사용자 입력 {@code preferredBpm} 이 없을 때 분위기로 추론할 default 표.
     * 분위기마저 없으면 {@link #fallbackBpm} 사용. 없으면 neutral 0.5 신호로 폴백.</li>
     * <li>{@code fallbackBpm}: mood 미입력일 때 사용할 최후 default. null이면 사용자 입력 없음/분위기 없음 시
     * tempoMatch=0.5(중립) — 결정성/단순성 유지.</li>
     * </ul>
     */
    public record Tempo(
            @DecimalMin("1.0") double distanceTolerance,
            @NotNull Map<Mood, @Min(30) @jakarta.validation.constraints.Max(300) Integer> moodDefaultBpm,
            Integer fallbackBpm
    ) {

        public Tempo {
            Objects.requireNonNull(moodDefaultBpm, "moodDefaultBpm must not be null");
            // 빈 입력도 허용(빈 EnumMap 으로 정규화) — fallbackBpm 만으로 동작 가능해야 한다.
            // `new EnumMap<>(Map)` 은 입력이 비어 있으면 IllegalArgumentException 을 던지므로
            // keyType 생성자로 빈 EnumMap 을 만든 뒤 putAll 한다.
            final EnumMap<Mood, Integer> defensive = new EnumMap<>(Mood.class);
            defensive.putAll(moodDefaultBpm);
            moodDefaultBpm = Map.copyOf(defensive);
            if (fallbackBpm != null && (fallbackBpm < 30 || fallbackBpm > 300)) {
                throw new IllegalArgumentException("fallbackBpm out of [30, 300]: " + fallbackBpm);
            }
        }
    }
}
