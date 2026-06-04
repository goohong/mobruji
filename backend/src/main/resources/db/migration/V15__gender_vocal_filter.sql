-- V15__gender_vocal_filter.sql — 성별 필터(남자곡/여자곡) 곡 속성 + 요청 입력 (#1767)
--
-- spec: docs/ai-harness/06-domain-model.md §5-2 Song / §5-3 RecommendationRequest.
-- 보호 영역 (CLAUDE.md §4) — rev 사이클 추가 신중도 가중.
--
-- 의도:
--   - song.vocal_gender: 곡 보컬 성별의 큐레이션 권위 속성(MALE/FEMALE/MIXED). nullable —
--     외부 임포트 곡은 미적재로 두고 추천 시점에 음역·키로 추정(후순위)한다.
--   - recommendation_request.gender: 추천 요청자가 고른 성별 필터(MALE/FEMALE). nullable —
--     미입력 시 genderFit=0 이 되어 랭킹에 영향이 없다(하위호환). age_group(V9)과 동일 패턴으로
--     결정성 seed 입력에 포함되어, 같은 voiceRange/sessionId 라도 gender 가 다르면 다른 결과를 보장한다.
--
-- 방언: MySQL 8.4 (운영). 테스트는 H2 MODE=MySQL.

ALTER TABLE song ADD COLUMN vocal_gender VARCHAR(16) NULL;
ALTER TABLE recommendation_request ADD COLUMN gender VARCHAR(16) NULL;
