package com.mobruji.recommendation.api.dto;

import java.util.List;
import java.util.Objects;

import com.mobruji.recommendation.application.MoodPresetView;

/**
 * 분위기 프리셋 목록 응답 wrapper.
 *
 * <p>배열을 직접 노출하지 않고 객체로 감싸 향후 메타(기본 프리셋/정렬 기준 등) 추가 시 키 변경 없이 진화 가능.
 * {@code presets} 는 {@link com.mobruji.recommendation.domain.MoodPreset} 선언 순서를 따른다.
 */
public record MoodPresetListResponse(
        List<MoodPresetResponse> presets
) {

    public static MoodPresetListResponse from(final List<MoodPresetView> views) {
        Objects.requireNonNull(views, "views must not be null");
        return new MoodPresetListResponse(
                views.stream().map(MoodPresetResponse::from).toList());
    }
}
