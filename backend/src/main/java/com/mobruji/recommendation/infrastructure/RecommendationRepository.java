package com.mobruji.recommendation.infrastructure;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mobruji.recommendation.domain.Recommendation;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {

    List<Recommendation> findByRecommendationRequestIdOrderByRankPositionAsc(Long recommendationRequestId);
}
