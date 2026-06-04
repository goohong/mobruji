package com.mobruji.recommendation.application;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import com.mobruji.recommendation.domain.AgeGroup;
import com.mobruji.song.domain.Mood;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
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
 * <li>{@code generation}: #1487 신규. 곡 발매연도 ↔ 요청 연령대 대표 시기 거리 기반 [0,1]. 연령대 미입력 시 신호값 0.</li>
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
        @NotNull @Valid Generation generation,
        @NotNull @Valid Gender gender,
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
            @DecimalMin("0.0") double tempoMatch,
            @DecimalMin("0.0") double generation,
            @DecimalMin("0.0") double gender
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

    /**
     * generationFit 신호 설정 (#1487).
     *
     * <ul>
     * <li>{@code distanceToleranceYears}: |songReleaseYear - representativeYear| 가 이 값 이상이면 score=0,
     * 그 미만이면 {@code 1.0 - distance/tolerance} 로 선형 감쇠.</li>
     * <li>{@code representativeYear}: 연령대별 "대표 시기" 발매연도(=그 세대의 곡이 많이 나온 시기 추정값). 곡 발매연도가
     * 이 값에 가까울수록 가산. 연령대가 표에 없으면 generationFit=0(가중 없음).</li>
     * </ul>
     *
     * <p>대표 시기는 "지금 기준 formative 연도" 추정이라 코드에 박지 않고 yml 로 외부화한다 — 운영 측정 후 튜닝 가능.
     * 결정성에는 영향 없음(설정 고정 시 같은 입력 → 같은 결과).
     */
    public record Generation(
            @DecimalMin("1.0") double distanceToleranceYears,
            @NotNull Map<AgeGroup, @Min(1900) @Max(2100) Integer> representativeYear
    ) {

        public Generation {
            Objects.requireNonNull(representativeYear, "representativeYear must not be null");
            // 빈 입력도 허용(빈 EnumMap 으로 정규화) — Tempo.moodDefaultBpm 과 동일 패턴.
            // `new EnumMap<>(Map)` 은 입력이 비어 있으면 IllegalArgumentException 을 던지므로
            // keyType 생성자로 빈 EnumMap 을 만든 뒤 putAll 한다.
            final EnumMap<AgeGroup, Integer> defensive = new EnumMap<>(AgeGroup.class);
            defensive.putAll(representativeYear);
            representativeYear = Map.copyOf(defensive);
        }
    }

    /**
     * genderFit 신호 설정 (#1767). 곡 보컬 성별과 요청 성별 필터(남자곡/여자곡)의 부분 적합 점수를 외부화한다.
     * 큐레이션 일치는 항상 1.0(고정), 그 외 비-일치 케이스의 가산 수준만 운영 측정 후 yml 로 튜닝한다.
     *
     * <ul>
     * <li>{@code estimatedMatchScore}: 큐레이션 부재 곡을 음역·키로 추정해 요청 성별과 일치할 때의 가산.
     * 큐레이션(1.0)보다 낮춰 추정 신뢰도 차이를 반영(후순위). 기본 0.6.</li>
     * <li>{@code mixedScore}: 큐레이션 {@code MIXED}(듀엣/혼성) 곡 — 남자곡/여자곡 어느 요청에도 부분 적합. 기본 0.5.</li>
     * <li>{@code unknownScore}: 큐레이션·추정 모두 불가(음역·키 부재) — 후순위 중립값. 기본 0.3.</li>
     * </ul>
     *
     * <p>반대 성별은 0.0(가산 없음) — 배타 제외가 아니라 음역대 등 다른 신호로 추천 풀에 잔존한다. 모든 값은 [0,1]
     * (ScoreBreakdown raw 신호 범위). 결정성에는 영향 없음(설정 고정 시 같은 입력 → 같은 결과).
     */
    public record Gender(
            @DecimalMin("0.0") @jakarta.validation.constraints.DecimalMax("1.0") double estimatedMatchScore,
            @DecimalMin("0.0") @jakarta.validation.constraints.DecimalMax("1.0") double mixedScore,
            @DecimalMin("0.0") @jakarta.validation.constraints.DecimalMax("1.0") double unknownScore
    ) {
    }
}
