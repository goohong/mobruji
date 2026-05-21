-- V4__recommendation_preferred_bpm.sql — recommendation_request 에 preferred_bpm 추가
--
-- spec: docs/features/recommendation-algorithm-v1.md §9 v2 결정 로그 (closes #218).
-- 보호 영역 (CLAUDE.md §4) — PR 에 `needs-human-review` 라벨 부여.
--
-- 의도:
--   - v2 tempoMatch 신호의 사용자 입력 'preferredBpm' 을 요청과 함께 영속한다.
--   - nullable: 사용자가 BPM 을 입력하지 않으면 mood 기반 default 로 폴백한다.
--   - 결정성 seed 입력에 포함되어 같은 voiceRange/sessionId 라도 preferredBpm 이 다르면
--     다른 jitter 시드 → 다른 결과를 보장한다 (SeedDeriver v2 회귀 가드).
--
-- 방언: MySQL 8.4 (운영). 테스트는 H2 MODE=MySQL.

ALTER TABLE recommendation_request ADD COLUMN preferred_bpm INTEGER NULL;
