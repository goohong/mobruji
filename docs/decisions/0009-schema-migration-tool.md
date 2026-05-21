---
id: 0009
title: Schema 마이그레이션 도구 = Flyway
status: accepted
date: 2026-05-21
deciders: [@goohong]
---

# 0009. Schema 마이그레이션 도구 = Flyway

## Context
현재 backend는 `application.yml`에서 `spring.jpa.hibernate.ddl-auto=validate`(운영 기본), `application-local.yml`에서 `update`(로컬 docker MySQL)로 운영 중이다. 문제:

- `validate`는 schema drift를 감지만 할 뿐 **버전 관리·이력·재현성**이 없다. 운영 진입(v0.2 mature) 시 schema 변경의 안전한 적용 경로가 없다.
- PR #74(JoinTable 도입), PR #114(엔티티 변경) 등에서 schema 변경이 누적됐고, 새 환경 부팅 시 어떤 순서로 어떤 변경이 들어가야 하는지 코드 베이스 어디에도 영속되지 않는다.
- 로컬 `update`는 entity 변경을 자동 반영하나 **운영에서는 부적합**(컬럼 drop/rename을 안전하게 못 함, prod 데이터 손실 위험).
- AI 세션이 엔티티를 바꿀 때 schema 변경 의도를 PR 단위로 명시·승인할 수 있는 채널이 필요하다.

v0.2 mature 진입 전, 마이그레이션 도구를 결정하고 baseline을 잡아야 한다.

## Decision
- **Flyway**(Apache 2.0)를 schema 마이그레이션 도구로 채택한다.
- `backend/build.gradle`에 `flyway-core` + `flyway-mysql` 의존성을 추가하고, Spring Boot 자동 통합(`spring-boot-starter-data-jpa` + Flyway auto-config)에 맡긴다.
- 마이그레이션 파일은 `backend/src/main/resources/db/migration/V{N}__{slug}.sql` 형식(SQL native, MySQL 8.4 방언).
- **Baseline**: 현재까지의 모든 엔티티(PR #74의 join table 포함)를 `V1__init.sql` 한 파일로 통합. 이후 변경은 `V2`, `V3`...로 누적.
- `spring.jpa.hibernate.ddl-auto`는 모든 프로필에서 `validate`로 고정. local 프로필의 `update`도 폐기 → Flyway가 schema의 단일 진실원.
- 테스트는 ADR 0003(test DB strategy)에 따라 H2/Testcontainers 사용. Flyway 마이그레이션은 양쪽 모두 동작해야 하며, MySQL 방언 차이로 호환 안 되는 경우 `db/migration/h2/` 분리(후속 PR에서 필요 시).

## Consequences
### 긍정적
- Schema 변경이 **PR 단위로 영속**된다. AI 세션의 엔티티 변경이 자동으로 마이그레이션 파일과 1:1 매칭되어 리뷰 가능.
- 새 환경 부팅이 결정적이다. `V1` 부터 순서대로 실행되어 재현성이 100%.
- Spring Boot 자동 통합으로 추가 설정 거의 없음(`build.gradle` 의존성 + `db/migration/` 디렉토리만 있으면 동작).
- SQL native라 MySQL 8.4 기능(generated column, JSON 등)을 그대로 쓸 수 있고, 학습 비용이 낮다.
- `validate` + Flyway 조합으로 **entity ↔ schema drift가 부팅 시 즉시 감지**된다.

### 부정적
- 새 dependency 추가(`backend/build.gradle` 변경 → 보호 영역, `needs-human-review` 라벨 필요).
- 엔티티 변경 시 마이그레이션 파일을 **수동으로 작성**해야 한다(Hibernate `update`처럼 자동 생성 X). AI 세션이 엔티티만 바꾸고 마이그레이션을 빼먹는 회귀 가능 → PR 체크리스트/Feature Spec에 반영 필요.
- local 프로필에서 `update` 편의가 사라진다. 엔티티 실험 시 마이그레이션 파일을 같이 만들어야 함.
- 이미 운영 DB가 있는 환경에서 도입할 때는 `flyway.baseline-on-migrate=true` + `baseline-version=1`로 진입해야 함(후속 PR에서 처리).

## Alternatives (considered)
- **(A) Liquibase** — XML/YAML/SQL 다 지원, rollback 명령이 강하다. 그러나 (1) 현재 팀 규모에서 rollback은 마이그레이션 파일 추가로도 충분하고, (2) XML/YAML changeSet 추상 레이어가 SQL 학습 자산을 우회시키며, (3) Spring Boot 통합은 Flyway와 동등 수준이다. 차별점 대비 학습 비용이 커서 채택하지 않음.
- **(B) Spring `ddl-auto=update` 유지** — 운영에서 컬럼 drop/rename 안전성 부재, 변경 이력 영속화 불가. 운영 진입 직전 단계에서 명백히 부적합. 채택하지 않음.
- **(C) 자체 SQL 스크립트 + 수동 실행** — 도구 의존성은 없으나 적용 이력 추적(`flyway_schema_history` 같은 테이블)을 직접 만들어야 한다. 차별점 없이 운영 부담만 큼. 채택하지 않음.
- **(D) jOOQ DDL / EBean DDL 등 ORM 내장 마이그레이션** — 현재 스택(Spring Data JPA + Hibernate)과 어긋남. 도입 시 ORM 교체 비용. 채택하지 않음.

## References
- ADR 0001 — 기술 스택(Spring Boot 3.5.3, Java 21, MySQL 8.4).
- ADR 0003 — 테스트 DB 전략. Flyway 마이그레이션은 테스트 경로에서도 동작해야 함.
- PR #74 — JoinTable 도입. `V1__init.sql` baseline 대상.
- PR #114 — 엔티티 변경. baseline 대상.
- `backend/src/main/resources/application.yml` — `ddl-auto=validate` 현재 설정.
- `backend/src/main/resources/application-local.yml` — `ddl-auto=update` (본 ADR로 폐기 예정).
- 후속: 별도 이슈로 be 사이클에서 Flyway 실제 도입(보호 영역 변경 큰 PR, `needs-human-review`).
