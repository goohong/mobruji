package com.mobruji.recommendation.infrastructure;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mobruji.recommendation.domain.Recommendation;
import com.mobruji.recommendation.domain.TrendingSongAggregate;
import com.mobruji.song.domain.Mood;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {

    List<Recommendation> findByRecommendationRequestIdOrderByRankPositionAsc(Long recommendationRequestId);

    /**
     * 여러 요청 ID 에 속한 결과 row 를 한 번에 조회 (history GET 의 N+1 회피).
     *
     * <p>호출 측에서 requestId 별로 그룹핑하고 rankPosition 으로 재정렬해 사용한다.
     */
    List<Recommendation> findByRecommendationRequestIdIn(Collection<Long> recommendationRequestIds);

    /**
     * 추천 결과 히스토리(다른 사용자 포함 전체)를 기간/분위기/음역대로 필터링해 곡별 인기도를 집계한다 (#1488 트렌딩).
     *
     * <p>{@code recommendation} 결과 row 를 {@code recommendation_request} 와 application 레벨 join 으로 묶어
     * (도메인 간 FK 미설정 정책과 일관) request 메타데이터(생성 시각/분위기/음역대)로 필터한다.
     *
     * <ul>
     * <li>{@code appearanceCount} — 기간 내 추천 결과에 곡이 등장한 횟수.</li>
     * <li>{@code popularityScore} — 등장마다 {@code 1.0 / rankPosition} 을 더한 rank 감쇠 합. 상위 노출(rank 1)이
     * 하위 노출보다 더 큰 가중을 받는다. "추천에 자주, 그리고 더 앞쪽에 뜬 곡" 일수록 높다.</li>
     * </ul>
     *
     * <p>차별점: {@code mood}/{@code rangeLow}/{@code rangeHigh} 는 모두 nullable 이며, 값이 있으면 노래방 일반 차트와
     * 달리 "해당 분위기" 또는 "내 음역대와 겹치는 요청"으로 좁혀 집계한다. 음역대는 구간 overlap
     * ({@code req.low <= rangeHigh AND req.high >= rangeLow}) 으로 판정한다.
     *
     * <p>정렬/limit 은 provider 별 alias ORDER BY 차이를 피해 호출 측(application)에서 결정성 있게 처리한다.
     */
    @Query("""
            SELECT r.songId AS songId, COUNT(r) AS appearanceCount, SUM(1.0 / r.rankPosition) AS popularityScore
            FROM Recommendation r, RecommendationRequestEntity req
            WHERE r.recommendationRequestId = req.id
              AND req.createdAt >= :since
              AND (:mood IS NULL OR req.mood = :mood)
              AND (:rangeLow IS NULL OR (req.voiceRangeLow <= :rangeHigh AND req.voiceRangeHigh >= :rangeLow))
            GROUP BY r.songId
            """)
    List<TrendingSongAggregate> aggregateTrending(
            @Param("since") LocalDateTime since,
            @Param("mood") Mood mood,
            @Param("rangeLow") Integer rangeLow,
            @Param("rangeHigh") Integer rangeHigh);
}
