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

import com.mobruji.recommendation.api.dto.DuetRecommendationCreateRequest;
import com.mobruji.recommendation.api.dto.DuetRecommendationResponse;
import com.mobruji.recommendation.api.dto.NextRecommendationRequest;
import com.mobruji.recommendation.api.dto.RecommendationCreateRequest;
import com.mobruji.recommendation.api.dto.RecommendationResponse;
import com.mobruji.recommendation.api.dto.SafeRecommendationCreateRequest;
import com.mobruji.recommendation.api.dto.SafeRecommendationResponse;
import com.mobruji.recommendation.api.dto.SequenceRecommendationCreateRequest;
import com.mobruji.recommendation.api.dto.SequenceRecommendationResponse;
import com.mobruji.recommendation.api.dto.ShowoffRecommendationCreateRequest;
import com.mobruji.recommendation.api.dto.ShowoffRecommendationResponse;
import com.mobruji.recommendation.api.dto.TrendingListResponse;
import com.mobruji.recommendation.application.TrendingQuery;
import com.mobruji.recommendation.domain.DuetRecommendationResult;
import com.mobruji.recommendation.domain.RecommendationResult;
import com.mobruji.recommendation.domain.SafeRecommendationResult;
import com.mobruji.recommendation.domain.SequenceRecommendationResult;
import com.mobruji.recommendation.domain.ShowoffRecommendationResult;
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

    /**
     * 모임 사회자형(P-D) 시퀀스 추천(persona-expansion-social-emotional.md §2/§5). 단일 곡 묶음이 아니라 자리 흐름
     * (워밍업 → 고조 → 마무리) 3단계를 단계별 다른 분위기로 산출한다. 응답 형상이 단일 추천과 달라(단계 묶음) 기존
     * {@code POST /recommendations} 확장이 아닌 별도 엔드포인트로 둔다(§8 Q1 선택지 b).
     */
    @PostMapping("/sequence")
    public ResponseEntity<SequenceRecommendationResponse> createSequence(
            @Valid @RequestBody final SequenceRecommendationCreateRequest sequenceRecommendationCreateRequest) {
        final SequenceRecommendationResult sequenceRecommendationResult = recommendationService.createSequence(
                sequenceRecommendationCreateRequest.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SequenceRecommendationResponse.from(sequenceRecommendationResult));
    }

    /**
     * 안전곡형(P-E) 추천(persona-expansion-social-emotional.md §2/§5). "안 망하고 무사히 넘기고 싶다" 의도에 맞춰
     * 쉬운 난이도(EASY 우위) + 느린 템포 + 음역 여유로 강편향한 안심 추천을 만든다. 응답에 페르소나 식별자 + 곡별 "안심 포인트"를
     * 함께 노출한다(설명 가능성). 응답 형상이 단일 추천과 달라(페르소나·안심 포인트) 기존 {@code POST /recommendations} 확장이
     * 아닌 별도 엔드포인트로 둔다(P-D 시퀀스와 같은 패턴).
     */
    @PostMapping("/safe")
    public ResponseEntity<SafeRecommendationResponse> createSafe(
            @Valid @RequestBody final SafeRecommendationCreateRequest safeRecommendationCreateRequest) {
        final SafeRecommendationResult safeRecommendationResult = recommendationService.createSafe(
                safeRecommendationCreateRequest.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SafeRecommendationResponse.from(safeRecommendationResult));
    }

    /**
     * 과시·킬링파트형(P-F) 추천(persona-expansion-social-emotional.md §2/§5). "고음 질러 박수받고 싶다" 의도에 맞춰
     * 사용자 최고음 근접 + 임팩트(에너지) + 어려운 난이도(HARD 우위)로 강편향한 과시 추천을 만든다(안전곡 P-E 의 반대축). 응답에
     * 페르소나 식별자 + 곡별 "킬링파트 안내"를 함께 노출한다(설명 가능성). 응답 형상이 단일 추천과 달라(페르소나·킬링파트 안내) 기존
     * {@code POST /recommendations} 확장이 아닌 별도 엔드포인트로 둔다(P-D 시퀀스·P-E 안전곡과 같은 패턴).
     */
    @PostMapping("/showoff")
    public ResponseEntity<ShowoffRecommendationResponse> createShowoff(
            @Valid @RequestBody final ShowoffRecommendationCreateRequest showoffRecommendationCreateRequest) {
        final ShowoffRecommendationResult showoffRecommendationResult = recommendationService.createShowoff(
                showoffRecommendationCreateRequest.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ShowoffRecommendationResponse.from(showoffRecommendationResult));
    }

    /**
     * 듀엣·함께 부르기형(P-G) 추천(persona-expansion-social-emotional.md §2/§5). "둘이/같이 부를 곡" 의도에 맞춰 두 사람 음역을
     * 모두 충족하는 듀엣곡을 만든다 — 큐레이션 듀엣곡(MIXED) 우위 + 두 음역 동시 충족도로 강편향한다. 응답에 페르소나 식별자 + 곡별
     * "파트 분담" 안내를 함께 노출한다(설명 가능성). 요청 형상이 2인 음역으로 단일 추천과 달라(다중 음역 입력) 기존
     * {@code POST /recommendations} 확장이 아닌 별도 엔드포인트로 둔다(P-D 시퀀스·P-E 안전곡·P-F 과시와 같은 패턴).
     */
    @PostMapping("/duet")
    public ResponseEntity<DuetRecommendationResponse> createDuet(
            @Valid @RequestBody final DuetRecommendationCreateRequest duetRecommendationCreateRequest) {
        final DuetRecommendationResult duetRecommendationResult = recommendationService.createDuet(
                duetRecommendationCreateRequest.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(DuetRecommendationResponse.from(duetRecommendationResult));
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
