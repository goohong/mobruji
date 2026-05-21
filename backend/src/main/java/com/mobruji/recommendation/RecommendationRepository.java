package com.mobruji.recommendation;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {

    List<Recommendation> findByRecommendationRequestIdOrderByRankPositionAsc(Long recommendationRequestId);
}
