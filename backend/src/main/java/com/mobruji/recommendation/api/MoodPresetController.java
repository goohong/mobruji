package com.mobruji.recommendation.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mobruji.recommendation.api.dto.MoodPresetListResponse;
import com.mobruji.recommendation.application.MoodPresetCatalog;

import lombok.RequiredArgsConstructor;

/**
 * 분위기 메이커 모드(F3) 프리셋 카탈로그 조회 엔드포인트.
 *
 * <p>spec: docs/features/mood-mode.md §5-2. 프리셋 picker 가 라벨 표시 + 추천 호출({@code mood}/
 * {@code preferredBpm}) 구성에 쓰는 BE 단일 출처. 읽기 전용 — 인증 불필요(익명).
 */
@RestController
@RequiredArgsConstructor
public class MoodPresetController {

    private final MoodPresetCatalog moodPresetCatalog;

    @GetMapping("/api/v1/mood-presets")
    public MoodPresetListResponse list() {
        return MoodPresetListResponse.from(moodPresetCatalog.list());
    }
}
