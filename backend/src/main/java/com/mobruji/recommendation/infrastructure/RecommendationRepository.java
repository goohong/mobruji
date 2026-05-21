package com.mobruji.recommendation.infrastructure;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mobruji.recommendation.domain.Recommendation;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {

    List<Recommendation> findByRecommendationRequestIdOrderByRankPositionAsc(Long recommendationRequestId);

    /**
     * 여러 요청 ID 에 속한 결과 row 를 한 번에 조회 (history GET 의 N+1 회피).
     *
     * <p>호출 측에서 requestId 별로 그룹핑하고 rankPosition 으로 재정렬해 사용한다.
     */
    List<Recommendation> findByRecommendationRequestIdIn(Collection<Long> recommendationRequestIds);
}
