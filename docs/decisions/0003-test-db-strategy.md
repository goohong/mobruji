---
id: 0003
title: 테스트 DB 전략 — H2(단위/슬라이스) + MySQL(통합/E2E)
status: accepted
date: 2026-05-21
deciders: [@goohong]
---

# 0003. 테스트 DB 전략 — H2(단위/슬라이스) + MySQL(통합/E2E)

## Context
세션 1에서 `MobrujiBackendApplicationTests.contextLoads()` 실행에 로컬 docker MySQL 기동이 필요했다. 단위/슬라이스 테스트마다 MySQL을 띄우면:
- 로컬 dev 진입 비용 큼 (`docker compose up -d` 매번)
- 테스트 부팅 느림 (Hikari + JPA 초기화)
- CI에서도 MySQL 서비스 컨테이너 기동 시간 누적

반면 통합/E2E는 실제 MySQL 8.4 방언(`utf8mb4_unicode_ci`, 인덱스, 트랜잭션 isolation 등) 검증이 필요해 H2로 대체 불가.

## Decision
- **단위 테스트** (`@SpringBootTest` 없는 순수 JUnit): DB 미사용. mock으로 충분.
- **슬라이스 테스트** (`@DataJpaTest`, `@WebMvcTest`): **H2 in-memory** (MySQL 호환 모드).
- **통합/E2E 테스트** (`@SpringBootTest` + RestAssured): **MySQL 8.x** (로컬 docker-compose 또는 CI 서비스 컨테이너).
- 분기 기준: `@ActiveProfiles("test")` → H2, 프로파일 미지정 또는 `integration` → MySQL.
- `application-test.yml`에 H2 설정 둔다. 통합 테스트는 `@ActiveProfiles` 생략 또는 `integration` 명시.

## Consequences
### 긍정적
- 슬라이스 테스트는 docker 의존 없이 IDE에서 즉시 실행 가능.
- 단위/슬라이스 부팅 시간 단축 (Hikari 미초기화 + H2 in-memory).
- CI 비용 절감: MySQL 서비스 컨테이너는 통합 테스트 job에만.

### 부정적
- H2와 MySQL의 SQL/방언 차이로 통합 시점에서야 발견되는 버그 가능 (예: `ON CONFLICT` vs `ON DUPLICATE KEY UPDATE`). 슬라이스 통과 ≠ 통합 통과를 항상 유념.
- 테스트 2단 구조(H2/MySQL)가 신규 멤버에게 학습 곡선.

## Alternatives (considered)
- **(A) 모든 테스트 MySQL** — 정확도↑이지만 슬라이스에도 docker 강제 → 진입 비용. 채택하지 않음.
- **(B) 모든 테스트 H2** — 빠르지만 방언 차이 검증 불가. 첫 운영 사고 가능성. 채택하지 않음.
- **(C) Testcontainers MySQL** — H2보다 정확하고 docker compose 의존 없음. 단 시작 시간 H2 대비 길고 첫 도입 비용 있음. v1 이후 재검토. 일단 H2 채택.

## References
- `docs/ai-harness/07-testing-guide.md §3-5` (DB: H2 또는 testcontainers, 첫 통합 PR ADR로 결정)
- 본 ADR이 그 "첫 통합 테스트 PR의 ADR" 역할
