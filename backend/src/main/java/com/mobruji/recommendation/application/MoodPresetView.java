package com.mobruji.recommendation.application;

import java.util.Objects;

import com.mobruji.recommendation.domain.MoodPreset;

/**
 * {@link MoodPreset} 와 그 프리셋이 해석한 preferredBpm 을 묶은 카탈로그 view.
 *
 * <p>spec: docs/features/mood-mode.md §5-1. preferredBpm 은 {@link MoodPresetCatalog} 가
 * {@code recommendation.tempo.moodDefaultBpm} 에서 프리셋의 {@link MoodPreset#mood()} 키로 해석한 값.
 */
public record MoodPresetView(
        MoodPreset preset,
        int preferredBpm
) {

    public MoodPresetView {
        Objects.requireNonNull(preset, "preset must not be null");
    }
}
