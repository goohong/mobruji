package com.mobruji.recommendation.application;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.mobruji.recommendation.domain.MoodPreset;
import com.mobruji.song.domain.Mood;

import lombok.RequiredArgsConstructor;

/**
 * 분위기 프리셋 카탈로그 조회 — 4 프리셋을 각자의 {@link Mood} + preferredBpm 으로 해석해 노출한다.
 *
 * <p>spec: docs/features/mood-mode.md §5-1, §5-3. preferredBpm 은 별도 BPM 표를 두지 않고
 * {@code recommendation.tempo.moodDefaultBpm}(프리셋의 {@link Mood} 키)에서 해석한다 — 프리셋 BPM
 * 의 단일 출처를 기존 tempo 신호 설정과 일치시켜 drift 를 막는다. moodDefaultBpm 에 키가 없으면
 * {@code fallbackBpm} 으로 떨어진다.
 */
@Service
@RequiredArgsConstructor
public class MoodPresetCatalog {

    private final RecommendationProperties recommendationProperties;

    public List<MoodPresetView> list() {
        return Arrays.stream(MoodPreset.values())
                .map(preset -> new MoodPresetView(preset, resolvePreferredBpm(preset)))
                .toList();
    }

    private int resolvePreferredBpm(final MoodPreset preset) {
        final Map<Mood, Integer> moodDefaultBpm = recommendationProperties.tempo().moodDefaultBpm();
        final Integer resolvedBpm = moodDefaultBpm.getOrDefault(
                preset.mood(), recommendationProperties.tempo().fallbackBpm());
        return Objects.requireNonNull(
                resolvedBpm, "preferredBpm unresolved for preset " + preset);
    }
}
