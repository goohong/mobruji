---
id: 0005
title: 패키지 구조 — BoundedContext × 4계층 (Hexagonal lite)
status: accepted
date: 2026-05-21
deciders: [@goohong]
---

# 0005. 패키지 구조 — BoundedContext × 4계층 (Hexagonal lite)

## Context
`backend/src/main/java/com/mobruji/{voice,song,recommendation}/`는 v0.x 초기 단순화로 flat 구조를 채택했다. Controller/Service/Repository/Entity/Exception이 한 패키지에 평면 배치되고 DTO만 `dto/` 하위에 분리돼 있다. 다음 문제가 표면화됐다:

- `CLAUDE.md §4 "계층 침범 금지"` 룰을 컴파일/CI 단에서 자동 방어할 방법이 없음 (Controller → Repository 직접 호출이 import만 다를 뿐 같은 패키지라 막히지 않음).
- `RecommendationRequestEntity`, `RecommendationProperties`, `SongSeedLoader`처럼 책임이 다른 클래스가 한 패키지에 섞여 신규 파일 위치 결정이 즉흥적.
- 향후 ArchUnit/Modulith 도입 시 계층 경계가 패키지로 식별 가능해야 함.

## Decision
- 모든 BoundedContext(이하 BC)는 다음 4계층 패키지를 갖는다.
  ```
  com.mobruji.<bc>.{domain, application, infrastructure, api}
  ```
- 현재 BC 목록: `voice`, `song`, `recommendation`. 횡단 코드는 필요해질 때 `com.mobruji.common.*`로 추가 (지금은 두지 않음).
- **계층 책임**
  - `domain` — 엔티티/값 객체/도메인 예외/도메인 서비스. 외부 의존 최소. JPA 어노테이션은 v0.x 한정으로 이 계층에 둔다(별도 도메인 모델 + ORM 매핑 클래스 분리는 overkill).
  - `application` — 유스케이스 서비스(`@Service`/`@Transactional`), 애플리케이션 포트(필요 시), 시드 로더, properties.
  - `infrastructure` — Spring Data JPA Repository 인터페이스/구현, 외부 시스템 어댑터(향후 ML/외부 API), 영속 매핑 보조.
  - `api` — Controller, request/response DTO, `@RestControllerAdvice` 등 표현계층.
- **계층 의존 방향**: `api → application → domain ← infrastructure`. `domain`은 다른 계층을 의존하지 않는다.
- v0.x 한정 완화 — Cross-BC 데이터 접근은 application 계층에서 다른 BC의 `infrastructure` repository를 직접 주입해 사용해도 된다(현재 `RecommendationService`가 `SongRepository`를 주입하는 형태). 본격적 anti-corruption layer/Port-Adapter 도입은 BC 수가 늘거나 모듈 분리 욕구가 생길 때 별도 ADR로 결정.

## Consequences
### 긍정적
- 신규 파일 배치 결정이 1초 룰("이 클래스의 책임이 어느 계층?")로 끝난다.
- 계층 침범이 import 한 줄로 가시화돼 PR 리뷰 부담이 줄고, ArchUnit 같은 자동 검증의 사전 조건이 갖춰진다.
- 단일 BC 내에서 책임이 분리돼 Service 비대화/`*Helper` 남발을 억제한다.
- Spring 생태계의 일반 관례와 일치해 신규 합류자(또는 AI 세션)의 학습 곡선이 낮다.

### 부정적
- 일회성 마이그레이션 PR(전 파일 패키지 이동)이 필요하다. PR size 룰(`docs/ai-harness/03-quality-gates.md`)에 형식적으로 위배되나, 일관성을 위해 한 번에 처리한다(예외 명시: ADR 0005 마이그레이션).
- 디렉토리 깊이가 +1 증가. IDE 네비게이션은 패키지 트리 펼침으로 해결.
- v0.x 한정 완화(JPA 엔티티 = 도메인 모델, cross-BC repository 직접 주입)는 미래에 별도 ADR로 다시 다뤄야 할 가능성이 있다.

## Alternatives (considered)
- **(A) Status quo (flat)** — 가장 단순하지만 위 문제(계층 침범 자동 방어 불가, 신규 파일 위치 임의성)가 누적. 채택하지 않음.
- **(B) DDD full (Aggregate Root, Domain Event, ACL, CQRS 등)** — 표현력 우수하나 v0.x PoC에 과한 보일러플레이트. BC가 3개뿐이고 도메인 이벤트 사용처가 없는 현 단계엔 overkill. 채택하지 않음.
- **(C) 계층 우선 패키지 (`com.mobruji.{controller,service,repository,entity}`)** — Spring 입문서 패턴. BC 경계가 흐려져 도메인 모듈 분리 시 비용 발생. `CLAUDE.md`의 "PR scope와 패키지 경로 일치" 룰과 충돌(scope는 BC 단위). 채택하지 않음.
- **(D) Spring Modulith** — 모듈 경계 자동 검증/이벤트 발행 라이브러리. 매력적이나 의존성 추가 + 학습 비용. v1 이후 재검토 (도입 시 별도 ADR).

## References
- 이슈 #82, 후속 마이그레이션 이슈 본 PR 본문 참고.
- `CLAUDE.md §4` 계층 침범 금지 룰.
- `docs/ai-harness/08-code-conventions.md §A-7` (본 ADR과 함께 신설) — 계층 의존 룰 + 파일 단위 마이그레이션 매핑.
- `docs/ai-harness/06-domain-model.md` — BC 정의.
