package com.mobruji.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@link RecommendationProperties} Bean Validation 동작 회귀.
 *
 * <p>의도: 잘못된 외부 설정 값이 바인딩되면 컨텍스트 기동이 fail-fast 한다는 것을 확인한다. PR #54 후속 — 검증 방식을
 * {@code Objects.requireNonNull}/{@code IllegalArgumentException}에서 {@link jakarta.validation.constraints} 어노테이션으로
 * 통일한 효과를 단언한다.
 */
class RecommendationPropertiesValidationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    @DisplayName("유효한 값으로 바인딩 시 컨텍스트가 정상 기동한다")
    void validBinding_succeeds() {
        contextRunner
                .withPropertyValues(validProps())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final RecommendationProperties properties = context.getBean(RecommendationProperties.class);
                    assertThat(properties.resultCount()).isEqualTo(10);
                    assertThat(properties.weights().voiceFit()).isEqualTo(0.5);
                });
    }

    @Test
    @DisplayName("resultCount=0 이면 @Min(1) 위반으로 startup fail")
    void invalidResultCount_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("recommendation.result-count=0"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("jitterMagnitude 음수면 @DecimalMin(0.0) 위반으로 startup fail")
    void invalidJitterMagnitude_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("recommendation.jitter-magnitude=-0.1"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("weights.voiceFit 음수면 nested @DecimalMin(0.0) 위반으로 startup fail")
    void invalidWeight_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("recommendation.weights.voice-fit=-0.1"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("diversity.maxSameArtist=0 이면 nested @Min(1) 위반으로 startup fail")
    void invalidDiversity_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("recommendation.diversity.max-same-artist=0"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("diversity.maxSameGenre=0 이면 nested @Min(1) 위반으로 startup fail")
    void invalidDiversityGenre_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("recommendation.diversity.max-same-genre=0"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("weights.tempoMatch 음수면 nested @DecimalMin(0.0) 위반으로 startup fail")
    void invalidWeightTempoMatch_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("recommendation.weights.tempo-match=-0.01"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("tempo.distanceTolerance<1.0 이면 @DecimalMin(1.0) 위반으로 startup fail")
    void invalidTempoTolerance_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("recommendation.tempo.distance-tolerance=0.5"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("tempo.moodDefaultBpm value<30 이면 nested @Min(30) 위반으로 startup fail")
    void invalidMoodBpmTooLow_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("recommendation.tempo.mood-default-bpm.UPBEAT=20"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("tempo.moodDefaultBpm value>300 이면 nested @Max(300) 위반으로 startup fail")
    void invalidMoodBpmTooHigh_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("recommendation.tempo.mood-default-bpm.UPBEAT=400"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("tempo.fallbackBpm 이 [30,300] 범위 밖이면 record constructor IAE → startup fail")
    void invalidFallbackBpm_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("recommendation.tempo.fallback-bpm=400"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    // ConfigurationProperties 바인딩 단계에서 record canonical constructor IAE 가 감싸진다.
                    assertThat(context.getStartupFailure()).isNotNull();
                });
    }

    @Test
    @DisplayName("tempo.fallbackBpm null 허용: 미지정이어도 정상 기동")
    void nullFallbackBpm_succeeds() {
        // validProps 와 동일하되 fallback-bpm 만 제거한 props 로 바인딩.
        final String[] propsWithoutFallback = filterProps("recommendation.tempo.fallback-bpm");
        contextRunner
                .withPropertyValues(propsWithoutFallback)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final RecommendationProperties properties = context.getBean(RecommendationProperties.class);
                    assertThat(properties.tempo().fallbackBpm()).isNull();
                });
    }

    @Test
    @DisplayName("tempo.moodDefaultBpm prefix entry 가 전혀 없으면 Map 자체가 null 로 바인딩되어 startup fail")
    void missingMoodDefaultBpm_failsStartup() {
        // 회귀 가드: ConfigurationProperties 바인딩은 Map prefix 가 하나도 없으면 Map 을 비어 있는 Map 이 아닌
        // null 로 채우고, Tempo canonical constructor 의 @NotNull / Objects.requireNonNull 에서 fail-fast 한다.
        // main 클래스 주석("빈 입력도 허용")이 가리키는 동작은 코드에서 `new Tempo(..., Map.of(), null)` 으로 직접
        // 생성할 때에 한정. 외부 설정 누락은 의도적으로 부트가 실패해야 한다.
        final String[] propsWithoutMoodMap = filterProps("recommendation.tempo.mood-default-bpm.");
        contextRunner
                .withPropertyValues(propsWithoutMoodMap)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("Tempo canonical constructor: 빈 Map 입력 시 defensive copy 로 정상 인스턴스화 — IAE 던지지 않음")
    void tempoConstructor_emptyMap_normalizes() {
        // 회귀 가드: `new EnumMap<>(Map)` 은 입력이 비어 있으면 IllegalArgumentException 을 던지지만,
        // Tempo canonical constructor 는 keyType 생성자 + putAll 로 우회한다. RecommendationScorerTest /
        // RecommendationDiversityTest 가 `Map.of()` 를 사용 중이라 직접 생성 경로의 회귀를 막는다.
        final RecommendationProperties.Tempo tempo = new RecommendationProperties.Tempo(
                40.0, java.util.Map.of(), 110);
        assertThat(tempo.moodDefaultBpm()).isEmpty();
        assertThat(tempo.fallbackBpm()).isEqualTo(110);
    }

    @Test
    @DisplayName("Tempo canonical constructor: fallbackBpm null 허용 + Map 입력값 보존")
    void tempoConstructor_nullFallback_preservesMap() {
        final java.util.Map<com.mobruji.song.domain.Mood, Integer> input = new java.util.EnumMap<>(
                com.mobruji.song.domain.Mood.class);
        input.put(com.mobruji.song.domain.Mood.UPBEAT, 128);
        final RecommendationProperties.Tempo tempo = new RecommendationProperties.Tempo(40.0, input, null);
        assertThat(tempo.moodDefaultBpm()).containsEntry(com.mobruji.song.domain.Mood.UPBEAT, 128);
        assertThat(tempo.fallbackBpm()).isNull();
    }

    @Test
    @DisplayName("Tempo canonical constructor: fallbackBpm 범위 밖이면 IllegalArgumentException")
    void tempoConstructor_invalidFallback_throws() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new RecommendationProperties.Tempo(40.0, java.util.Map.of(), 400))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fallbackBpm out of");
    }

    @Test
    @DisplayName("seedStrategy=random 도 enum 바인딩 정상")
    void seedStrategyRandom_binds() {
        contextRunner
                .withPropertyValues(propsWithOverrides("recommendation.seed-strategy=random"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final RecommendationProperties properties = context.getBean(RecommendationProperties.class);
                    assertThat(properties.seedStrategy()).isEqualTo(RecommendationProperties.SeedStrategy.RANDOM);
                });
    }

    // --- Tempo 경계값 회귀 가드 (issue #548) ---
    // 의도: PR #465 에서 잡지 못한 정확한 경계값(상·하한 통과 / 직전·직후 위반)을 명시한다.
    // distanceTolerance @DecimalMin("1.0"), moodDefaultBpm value @Min(30)/@Max(300),
    // fallbackBpm 은 record canonical constructor 의 [30,300] 가드.

    @Test
    @DisplayName("tempo.fallbackBpm=29 → record IAE → startup fail (하한 직전)")
    void fallbackBpm_belowMin_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("recommendation.tempo.fallback-bpm=29"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isNotNull();
                });
    }

    @Test
    @DisplayName("tempo.fallbackBpm=30 → 정확한 하한, 정상 기동")
    void fallbackBpm_atMin_succeeds() {
        contextRunner
                .withPropertyValues(propsWithOverrides("recommendation.tempo.fallback-bpm=30"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final RecommendationProperties properties = context.getBean(RecommendationProperties.class);
                    assertThat(properties.tempo().fallbackBpm()).isEqualTo(30);
                });
    }

    @Test
    @DisplayName("tempo.fallbackBpm=300 → 정확한 상한, 정상 기동")
    void fallbackBpm_atMax_succeeds() {
        contextRunner
                .withPropertyValues(propsWithOverrides("recommendation.tempo.fallback-bpm=300"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final RecommendationProperties properties = context.getBean(RecommendationProperties.class);
                    assertThat(properties.tempo().fallbackBpm()).isEqualTo(300);
                });
    }

    @Test
    @DisplayName("tempo.fallbackBpm=301 → record IAE → startup fail (상한 직후)")
    void fallbackBpm_aboveMax_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("recommendation.tempo.fallback-bpm=301"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isNotNull();
                });
    }

    @Test
    @DisplayName("tempo.distanceTolerance=1.0 → 정확한 하한, 정상 기동")
    void distanceTolerance_atMin_succeeds() {
        contextRunner
                .withPropertyValues(propsWithOverrides("recommendation.tempo.distance-tolerance=1.0"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final RecommendationProperties properties = context.getBean(RecommendationProperties.class);
                    assertThat(properties.tempo().distanceTolerance()).isEqualTo(1.0);
                });
    }

    @Test
    @DisplayName("tempo.distanceTolerance=0.0 → @DecimalMin(1.0) 위반으로 startup fail")
    void distanceTolerance_zero_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("recommendation.tempo.distance-tolerance=0.0"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("tempo.distanceTolerance=0.999 → @DecimalMin(1.0) 위반으로 startup fail (1.0 직전)")
    void distanceTolerance_justBelowMin_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("recommendation.tempo.distance-tolerance=0.999"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("tempo.moodDefaultBpm.UPBEAT=30 → 정확한 @Min 하한, 정상 기동")
    void moodDefaultBpm_atMin_succeeds() {
        contextRunner
                .withPropertyValues(propsWithOverrides("recommendation.tempo.mood-default-bpm.UPBEAT=30"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final RecommendationProperties properties = context.getBean(RecommendationProperties.class);
                    assertThat(properties.tempo().moodDefaultBpm())
                            .containsEntry(com.mobruji.song.domain.Mood.UPBEAT, 30);
                });
    }

    @Test
    @DisplayName("tempo.moodDefaultBpm.UPBEAT=29 → @Min(30) 위반으로 startup fail (하한 직전)")
    void moodDefaultBpm_justBelowMin_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("recommendation.tempo.mood-default-bpm.UPBEAT=29"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    @Test
    @DisplayName("tempo.moodDefaultBpm.UPBEAT=301 → @Max(300) 위반으로 startup fail (상한 직후)")
    void moodDefaultBpm_justAboveMax_failsStartup() {
        contextRunner
                .withPropertyValues(propsWithOverrides("recommendation.tempo.mood-default-bpm.UPBEAT=301"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                });
    }

    private static String[] validProps() {
        return new String[]{
                "recommendation.weights.voice-fit=0.5",
                "recommendation.weights.genre=0.2",
                "recommendation.weights.mood=0.2",
                "recommendation.weights.popularity=0.1",
                "recommendation.weights.tempo-match=0.1",
                "recommendation.weights.generation=0.15",
                "recommendation.weights.gender=0.1",
                "recommendation.diversity.max-same-artist=2",
                "recommendation.diversity.max-same-genre=4",
                "recommendation.tempo.distance-tolerance=40",
                "recommendation.tempo.mood-default-bpm.UPBEAT=128",
                "recommendation.tempo.mood-default-bpm.CALM=70",
                "recommendation.tempo.fallback-bpm=110",
                "recommendation.generation.distance-tolerance-years=15",
                "recommendation.generation.representative-year.TWENTIES=2015",
                "recommendation.generation.representative-year.FORTIES=1995",
                "recommendation.gender.estimated-match-score=0.6",
                "recommendation.gender.mixed-score=0.5",
                "recommendation.gender.unknown-score=0.3",
                "recommendation.result-count=10",
                "recommendation.jitter-magnitude=0.01",
                "recommendation.seed-strategy=derived"
        };
    }

    private static String[] propsWithOverrides(final String... overrides) {
        final String[] base = validProps();
        final String[] merged = new String[base.length + overrides.length];
        System.arraycopy(base, 0, merged, 0, base.length);
        System.arraycopy(overrides, 0, merged, base.length, overrides.length);
        return merged;
    }

    /**
     * {@link #validProps()} 에서 key 가 {@code keyPrefix} 로 시작하는 항목을 제거한다. 정확한 key 비교가 아닌 prefix 매치인
     * 이유: {@code recommendation.tempo.mood-default-bpm.UPBEAT} / {@code .CALM} 등 같은 prefix 의 여러 entry 를 한 번에
     * 제거하기 위함.
     */
    private static String[] filterProps(final String keyPrefix) {
        final String[] base = validProps();
        return java.util.Arrays.stream(base)
                .filter(prop -> !prop.startsWith(keyPrefix))
                .toArray(String[]::new);
    }

    @EnableConfigurationProperties(RecommendationProperties.class)
    static class TestConfig {
    }
}
