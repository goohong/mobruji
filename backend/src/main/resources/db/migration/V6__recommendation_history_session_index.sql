-- V6__recommendation_history_session_index.sql — recommendation_request 에 (session_id, created_at)
-- 보조 인덱스 추가. 세션별 추천 히스토리 조회(GET /api/v1/sessions/{id}/recommendation-history)
-- 쿼리의 인덱스 스캔 보장용.
--
-- spec: docs/features/recommendation-history-and-feedback.md §5-2, §5-5 (closes #236, spec PR C).
-- 보호 영역 (CLAUDE.md §4) — PR 에 `needs-human-review` 라벨 부여.
--
-- 의도:
--   - 결과 영속 엔티티({@code Recommendation} → 테이블 `recommendation`)는 V1 에서 이미 정규형으로 존재한다.
--     spec 의 `RecommendationResultEntry`(요청 ↔ N결과)는 코드 상 기존 `Recommendation` 엔티티가
--     동일 역할(id, recommendation_request_id, song_id, score, match_reason, rank_position, created_at)
--     을 충실히 수행하므로, 별도 테이블을 신설하지 않는다.
--   - 본 마이그레이션의 단일 변경점은 history 조회 쿼리(`recommendation_request.session_id` 필터
--     + 생성일 정렬)를 위한 보조 인덱스다. 결정성 영향 없음 (read-side 최적화 전용).
--
-- 방언: MySQL 8.4 (운영). 테스트는 H2 MODE=MySQL. 두 방언 모두 CREATE INDEX 구문 동일.

CREATE INDEX ix_recommendation_request_session_created
    ON recommendation_request (session_id, created_at);
