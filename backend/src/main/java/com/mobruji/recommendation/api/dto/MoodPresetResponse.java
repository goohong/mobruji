package com.mobruji.recommendation.api.dto;

import java.util.Objects;

import com.mobruji.recommendation.application.MoodPresetView;
import com.mobruji.recommendation.domain.MoodPreset;
import com.mobruji.song.domain.Mood;

/**
 * 분위기 프리셋 1건 응답 — fe 프리셋 picker 가 라벨 표시 + 추천 호출({@code mood}/{@code preferredBpm})
 * 구성에 쓰는 BE 단일 출처.
 *
 * <p>spec: docs/features/mood-mode.md §5-1, §5-2. {@code preset} 은 {@link MoodPreset} 이름,
 * {@code label} 은 사용자 노출 라벨, {@code mood}/{@code preferredBpm} 은 추천 요청에 그대로 실린다.
 */
public record MoodPresetResponse(
        String preset,
        String label,
        Mood mood,
        int preferredBpm
) {

    public static MoodPresetResponse from(final MoodPresetView view) {
        Objects.requireNonNull(view, "view must not be null");
        final MoodPreset preset = view.preset();
        return new MoodPresetResponse(
                preset.name(),
                preset.displayName(),
                preset.mood(),
                view.preferredBpm());
    }
}
