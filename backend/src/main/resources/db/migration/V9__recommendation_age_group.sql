-- V9__recommendation_age_group.sql — recommendation_request 에 age_group 추가
--
-- spec: docs/ai-harness/06-domain-model.md §5 RecommendationRequest (closes #1487).
-- 보호 영역 (CLAUDE.md §4) — rev 사이클 추가 신중도 가중.
--
-- 의도:
--   - generationFit 신호의 사용자 입력 'ageGroup'(연령대) 을 요청과 함께 영속한다.
--   - nullable: 미입력 시 generationFit=0 이 되어 랭킹에 영향이 없다 (하위호환).
--   - 결정성 seed 입력에 포함되어 같은 voiceRange/sessionId 라도 ageGroup 이 다르면
--     다른 jitter 시드 → 다른 결과를 보장한다 (SeedDeriver 회귀 가드).
--
-- 방언: MySQL 8.4 (운영). 테스트는 H2 MODE=MySQL.

ALTER TABLE recommendation_request ADD COLUMN age_group VARCHAR(16) NULL;
