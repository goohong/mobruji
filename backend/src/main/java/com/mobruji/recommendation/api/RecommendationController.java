package com.mobruji.recommendation.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mobruji.recommendation.api.dto.NextRecommendationRequest;
import com.mobruji.recommendation.api.dto.RecommendationCreateRequest;
import com.mobruji.recommendation.api.dto.RecommendationResponse;
import com.mobruji.recommendation.api.dto.TrendingListResponse;
import com.mobruji.recommendation.application.TrendingQuery;
import com.mobruji.recommendation.domain.RecommendationResult;
import com.mobruji.song.domain.Mood;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.mobruji.recommendation.application.RecommendationService;
import com.mobruji.recommendation.application.TrendingService;

@RestController
@RequestMapping("/api/v1/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    /**
     * 트렌딩 집계 기간 상한(일). 무제한 기간 스캔을 막는 운영 가드. 기본값은 {@code periodDays} 미입력 시 7일.
     */
    private static final int MAX_PERIOD_DAYS = 365;

    /**
     * 트렌딩 결과 개수 상한. fe 한 화면 노출 + 집계 비용 가드. 기본값은 {@code limit} 미입력 시 10.
     */
    private static final int MAX_LIMIT = 100;

    private final RecommendationService recommendationService;
    private final TrendingService trendingService;

    @PostMapping
    public ResponseEntity<RecommendationResponse> create(
            @Valid @RequestBody final RecommendationCreateRequest recommendationCreateRequest) {
        final RecommendationResult recommendationResult = recommendationService.create(
                recommendationCreateRequest.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED).body(RecommendationResponse.from(recommendationResult));
    }

    @PostMapping("/next")
    public ResponseEntity<RecommendationResponse> createFromSeeds(
            @Valid @RequestBody final NextRecommendationRequest nextRecommendationRequest) {
        final RecommendationResult recommendationResult = recommendationService.createFromSeeds(
                nextRecommendationRequest.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED).body(RecommendationResponse.from(recommendationResult));
    }

    @GetMapping("/{id}")
    public RecommendationResponse read(@PathVariable final Long id) {
        return RecommendationResponse.from(recommendationService.readById(id));
    }

    /**
     * 트렌딩(다른 사용자 인기곡) 조회 (#1488). 추천 결과 히스토리를 기간/분위기/음역대로 집계한 인기곡 순위.
     *
     * <p>spec: {@code docs/features/trending-recommendation.md}. query param 은 모두 옵션:
     * <ul>
     * <li>{@code periodDays}(기본 7) — 최근 N일. 1..{@value #MAX_PERIOD_DAYS}.</li>
     * <li>{@code mood} — 분위기 필터. 노래방 일반 차트와 달리 "해당 분위기에서 뜨는 곡"으로 좁힌다.</li>
     * <li>{@code voiceRangeLow}/{@code voiceRangeHigh} — 둘 다 또는 모두 미입력. "내 음역대와 겹치는 추천"만 집계.</li>
     * <li>{@code limit}(기본 10) — 1..{@value #MAX_LIMIT}.</li>
     * </ul>
     * 잘못된 조합(범위 역전 / 한쪽만 입력 / 범위 초과)은 400. 잘못된 {@code mood} 값은 Spring 변환 단에서 400.
     */
    @GetMapping("/trending")
    public TrendingListResponse trending(
            @RequestParam(name = "periodDays", defaultValue = "7") final int periodDays,
            @RequestParam(name = "mood", required = false) final Mood mood,
            @RequestParam(name = "voiceRangeLow", required = false) final Integer voiceRangeLow,
            @RequestParam(name = "voiceRangeHigh", required = false) final Integer voiceRangeHigh,
            @RequestParam(name = "limit", defaultValue = "10") final int limit) {
        validateRange(periodDays, 1, MAX_PERIOD_DAYS, "periodDays");
        validateRange(limit, 1, MAX_LIMIT, "limit");
        if ((voiceRangeLow == null) != (voiceRangeHigh == null)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "voiceRangeLow 와 voiceRangeHigh 는 함께 입력해야 합니다.");
        }
        if (voiceRangeLow != null && voiceRangeLow > voiceRangeHigh) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "voiceRangeLow (" + voiceRangeLow + ") 는 voiceRangeHigh (" + voiceRangeHigh
                            + ") 보다 작거나 같아야 합니다.");
        }
        final TrendingQuery trendingQuery = new TrendingQuery(periodDays, mood, voiceRangeLow, voiceRangeHigh, limit);
        return TrendingListResponse.of(
                periodDays, mood, voiceRangeLow, voiceRangeHigh, trendingService.getTrending(trendingQuery));
    }

    private static void validateRange(final int value, final int min, final int max, final String fieldName) {
        if (value < min || value > max) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, fieldName + " 는 " + min + " 이상 " + max + " 이하여야 합니다: " + value);
        }
    }
}
