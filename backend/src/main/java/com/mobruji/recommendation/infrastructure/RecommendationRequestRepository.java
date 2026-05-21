package com.mobruji.recommendation.infrastructure;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mobruji.recommendation.domain.RecommendationRequestEntity;

public interface RecommendationRequestRepository extends JpaRepository<RecommendationRequestEntity, Long> {

    /**
     * 세션별 추천 요청을 최신순(createdAt DESC, id DESC tiebreaker)으로 반환한다.
     *
     * <p>spec: docs/features/recommendation-history-and-feedback.md §5-2 — history GET 엔드포인트 백킹 쿼리.
     * V6 마이그레이션의 {@code (session_id, created_at)} 인덱스로 풀스캔 회피.
     * id 보조 정렬은 동일 ms 에 연속 요청이 들어와 createdAt 충돌하는 경우의 안정 정렬 보장.
     */
    List<RecommendationRequestEntity> findBySessionIdOrderByCreatedAtDescIdDesc(String sessionId);
}
