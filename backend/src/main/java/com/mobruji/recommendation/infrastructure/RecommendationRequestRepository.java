package com.mobruji.recommendation.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mobruji.recommendation.domain.RecommendationRequestEntity;

public interface RecommendationRequestRepository extends JpaRepository<RecommendationRequestEntity, Long> {
}
