package com.mobruji.recommendation.infrastructure;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.EntityGraph.EntityGraphType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mobruji.recommendation.domain.RecommendationRequestEntity;

public interface RecommendationRequestRepository extends JpaRepository<RecommendationRequestEntity, Long> {

    /**
     * 세션별 추천 요청을 최신순(createdAt DESC, id DESC tiebreaker)으로 반환한다.
     *
     * <p>spec: docs/features/recommendation-history-and-feedback.md §5-2 — history GET 엔드포인트 백킹 쿼리.
     * V6 마이그레이션의 {@code (session_id, created_at)} 인덱스로 풀스캔 회피.
     * id 보조 정렬은 동일 ms 에 연속 요청이 들어와 createdAt 충돌하는 경우의 안정 정렬 보장.
     *
     * <p>{@link EntityGraph} (FETCH, 빈 attributePaths) 적용 — {@code excludeSongIds} 의
     * {@code ElementCollection(EAGER)} default fetch 를 호출별로 LAZY 로 오버라이드해 N+1 차단.
     * history 응답({@code RecommendationHistoryResponse}) 은 excludeSongIds 를 노출하지 않으므로
     * 별도 collection select 가 dead fetch. closes #409 M5 — rev sub-agent 2026-05-23 발견.
     */
    @EntityGraph(type = EntityGraphType.FETCH, attributePaths = {})
    List<RecommendationRequestEntity> findBySessionIdOrderByCreatedAtDescIdDesc(String sessionId);

    /**
     * 한 세션의 이전 요청들에서 제외/부른 곡으로 영속된 {@code excludeSongIds} 를 중복 없이 1쿼리로 조회한다 (#1549).
     *
     * <p>"부른 곡 기반 다음곡 추천"({@code /next}) 흐름에서 seed 곡은 {@code excludeSongIds} 에 합쳐져 영속되므로,
     * 이 쿼리는 "이전에 부른 곡 + 명시 제외한 곡" 을 함께 회수한다. {@code recommended} 곡(결과 row) 과 합쳐
     * 세션 단위 누적 제외를 구성한다. {@code ElementCollection} 을 JOIN 으로 펼쳐 {@code DISTINCT} — 요청별
     * lazy collection 접근(N+1) 대신 단일 select.
     */
    @Query("""
            SELECT DISTINCT songId
            FROM RecommendationRequestEntity req
            JOIN req.excludeSongIds songId
            WHERE req.sessionId = :sessionId
            """)
    List<Long> findDistinctExcludeSongIdsBySessionId(@Param("sessionId") String sessionId);
}
