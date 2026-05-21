package com.mobruji.recommendation;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationRequestRepository extends JpaRepository<RecommendationRequestEntity, Long> {
}
