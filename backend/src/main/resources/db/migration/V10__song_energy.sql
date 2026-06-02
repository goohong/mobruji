-- V10__song_energy.sql — Song 에 energy(음향 에너지) 컬럼 추가
--
-- spec: docs/features/song-analysis-data-and-consumers.md §5-1/§5-5 (PR 1 스키마 단계, #1490).
-- 보호 영역 (CLAUDE.md §4) — rev 사이클 추가 신중도 가중.
--
-- 의도:
--   - 곡의 음향 에너지/강렬함 0.0~1.0 (nullable). self-analysis pivot 의 energy 차원을
--     스키마 + 수기/시드 적재 스캐폴드로 먼저 채운다. 자동 산출은 후속(§8 Q2).
--   - nullable: 미적재 곡은 소비자(#1485/#1486)가 graceful degrade (energy 가중 0) → 결정성 회귀 없음.
--   - DECIMAL(3,2): 0.00~1.00 표현. 추천 점수 산식 입력 아님 (적재만으로 랭킹 불변).
--
-- 방언: MySQL 8.4 (운영). 테스트는 H2 MODE=MySQL.

ALTER TABLE song ADD COLUMN energy DECIMAL(3,2) NULL;
