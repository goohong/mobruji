package com.mobruji.recommendation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mobruji.recommendation.dto.RecommendationCreateRequest;
import com.mobruji.recommendation.dto.RecommendationResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;

    @PostMapping
    public ResponseEntity<RecommendationResponse> create(
            @Valid @RequestBody final RecommendationCreateRequest recommendationCreateRequest) {
        final RecommendationResponse recommendationResponse = recommendationService.create(recommendationCreateRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(recommendationResponse);
    }

    @GetMapping("/{id}")
    public RecommendationResponse read(@PathVariable final Long id) {
        return recommendationService.readById(id);
    }
}
