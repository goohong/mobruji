package com.mobruji.recommendation.domain;

/**
 * 트렌딩 집계 쿼리 1행의 Spring Data 프로젝션 (#1488). 곡별 추천 등장 횟수와 rank 감쇠 인기도 합.
 *
 * <p>곡 메타데이터(title/artist 등)는 포함하지 않는다 — 집계 단계에서는 곡 ID 만 들고, application 계층이
 * {@code findAllById} 로 메타를 별도 join 해 N+1 없이 응답을 구성한다.
 */
public interface TrendingSongAggregate {

    Long getSongId();

    long getAppearanceCount();

    double getPopularityScore();
}
