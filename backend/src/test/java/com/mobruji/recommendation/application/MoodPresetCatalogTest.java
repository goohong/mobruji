package com.mobruji.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mobruji.recommendation.domain.MoodPreset;
import com.mobruji.song.domain.Mood;

/**
 * {@link MoodPresetCatalog} 단위 테스트 — 프리셋이 preferredBpm 을
 * {@code recommendation.tempo.moodDefaultBpm} 에서 자기 {@link Mood} 키로 해석하는지, 키 부재 시
 * fallbackBpm 으로 떨어지는지 검증한다.
 *
 * <p>spec: docs/features/mood-mode.md §5-1, §7.
 */
class MoodPresetCatalogTest {

    private static final RecommendationProperties.Diversity DIVERSITY = new RecommendationProperties.Diversity(2, 4);

    private static final RecommendationProperties.Generation GENERATION = new RecommendationProperties.Generation(15.0,
            Map.of());

    private static RecommendationProperties propertiesWithTempo(final RecommendationProperties.Tempo tempo) {
        return new RecommendationProperties(
                new RecommendationProperties.Weights(0.5, 0.2, 0.3, 0.1, 0.1, 0.15),
                DIVERSITY, tempo, GENERATION, 10, 0.01,
                RecommendationProperties.SeedStrategy.DERIVED);
    }

    @Test
    @DisplayName("프리셋 4종이 각자 Mood 의 moodDefaultBpm 으로 preferredBpm 해석")
    void list_resolvesPreferredBpmFromMoodDefaultBpm() {
        final RecommendationProperties.Tempo tempo = new RecommendationProperties.Tempo(
                40.0,
                Map.of(
                        Mood.UPBEAT, 128,
                        Mood.POWERFUL, 140,
                        Mood.EMOTIONAL, 80,
                        Mood.GROOVY, 110),
                90);
        final MoodPresetCatalog catalog = new MoodPresetCatalog(propertiesWithTempo(tempo));

        final List<MoodPresetView> views = catalog.list();

        assertThat(views)
                .extracting(MoodPresetView::preset, MoodPresetView::preferredBpm)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(MoodPreset.PARTY, 128),
                        org.assertj.core.groups.Tuple.tuple(MoodPreset.SINGALONG, 140),
                        org.assertj.core.groups.Tuple.tuple(MoodPreset.EMOTIONAL, 80),
                        org.assertj.core.groups.Tuple.tuple(MoodPreset.ICEBREAKER, 110));
    }

    @Test
    @DisplayName("moodDefaultBpm 에 프리셋 Mood 키가 없으면 fallbackBpm 으로 해석")
    void list_fallsBackToFallbackBpmWhenMoodAbsent() {
        final RecommendationProperties.Tempo tempo = new RecommendationProperties.Tempo(
                40.0, Map.of(Mood.UPBEAT, 128), 99);
        final MoodPresetCatalog catalog = new MoodPresetCatalog(propertiesWithTempo(tempo));

        final List<MoodPresetView> views = catalog.list();

        assertThat(views)
                .filteredOn(view -> view.preset() == MoodPreset.SINGALONG)
                .singleElement()
                .extracting(MoodPresetView::preferredBpm)
                .isEqualTo(99);
    }
}
