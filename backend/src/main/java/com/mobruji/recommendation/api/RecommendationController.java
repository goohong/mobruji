package com.mobruji.recommendation.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mobruji.recommendation.api.dto.NextRecommendationRequest;
import com.mobruji.recommendation.api.dto.RecommendationCreateRequest;
import com.mobruji.recommendation.api.dto.RecommendationResponse;
import com.mobruji.recommendation.domain.RecommendationResult;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.mobruji.recommendation.application.RecommendationService;

@RestController
@RequestMapping("/api/v1/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;

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
}
