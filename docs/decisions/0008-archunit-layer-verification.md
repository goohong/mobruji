---
id: 0008
title: ArchUnit으로 계층 의존 자동 검증 (ADR 0005 §A-7 가드)
status: accepted
date: 2026-05-21
deciders: [@goohong]
---

# 0008. ArchUnit으로 계층 의존 자동 검증 (ADR 0005 §A-7 가드)

## Context
ADR 0005 §A-7은 `api → application → domain ← infrastructure` 4계층 의존 방향을 텍스트 룰로만 규정했다. 텍스트 룰 단독은 다음 한계를 드러냈다:

- PR #89(패키지 구조 마이그레이션)에서 `application` 코드가 `api.dto`를 import하는 위반이 **8건** 발견됐다. 사람 리뷰가 못 막은 부분이며, IDE/컴파일러도 같은 모듈 안의 import는 차단하지 않는다.
- AI 세션이 신규 파일을 생성할 때 계층 경계 인식이 매번 균일하지 않다. `08-code-conventions.md §A-7`의 "위반 예시"를 참조해도 PR 단위 회귀를 0으로 만들 보장이 없다.
- ADR 0005가 "ArchUnit/Modulith 도입은 계층 경계 패키지화의 후속 가능성"으로 명시해 둔 상태.

테스트 단계에서 의존 그래프를 패키지 패턴으로 강제 검증하는 자동 가드가 필요하다.

## Decision
- **ArchUnit 1.3.0**(Apache 2.0)을 `backend/build.gradle`의 `testImplementation`으로 추가한다.
- `backend/src/test/java/com/mobruji/architecture/LayerDependencyTest.java`에 단일 `@AnalyzeClasses` 테스트를 둔다. 패키지 패턴(`..api..`, `..application..`, `..domain..`, `..infrastructure..`)으로 4계층을 정의하고, `layeredArchitecture()` DSL로 ADR 0005 §A-7의 의존 방향을 1:1 매핑해 강제한다.
- 검증 범위는 **BC를 통합한 전체 클래스패스** (`packages = "com.mobruji"`). v0.x 한정 완화(cross-BC `application → infrastructure` 허용)와 일관되게, BC 단위 분리 검증은 도입하지 않는다.
- 위반 발견 시 `./gradlew test`가 실패해 CI에서 PR 머지가 차단된다.

## Consequences
### 긍정적
- ADR 0005 §A-7 위반이 **사람 리뷰 의존 → CI 자동 차단**으로 전환된다. PR #89류 회귀를 코드 단계에서 0으로 만든다.
- 룰이 `LayerDependencyTest` 단일 파일에 영속되어, ADR 문서와 코드 룰이 한 PR에서 같이 갱신되는 강제성이 생긴다 (문서-코드 drift 차단).
- ArchUnit DSL이 ADR 0005 §A-7 의존 방향과 1:1 매핑되어 가독성이 높다. 신규 합류자(또는 AI 세션)가 룰을 ADR과 테스트 양쪽에서 동일 표현으로 확인할 수 있다.
- 후속 룰 확장 여지가 크다(cyclic dependency 금지, controller 어노테이션 검증, DTO 위치 검증 등) — 후속 ADR 없이도 같은 파일에 룰을 추가하는 것으로 점진 강화 가능.

### 부정적
- `./gradlew test` 시간이 ArchUnit 임포트 단계만큼 증가한다(현재 backend 규모에서 무시 가능 수준).
- 패키지 패턴 매칭이라 `..api..` 토큰을 다른 의미로 쓰는 클래스(예: 가상의 `apiclient` 패키지)가 생기면 룰이 오인식한다. BC 명에 `api/application/domain/infrastructure`를 쓰지 않는 컨벤션을 유지해야 한다.
- v0.x 단축형(cross-BC `application → infrastructure` 허용)이 룰에 내장돼 있어, 본격 hexagonal 분리로 갈 때는 본 ADR과 함께 룰 표현도 다시 잡아야 한다.

## Alternatives (considered)
- **(A) 사람 리뷰만** — PR #89에서 8건 누락으로 이미 입증된 한계. AI 세션 자율 운영 비중이 커지는 흐름과도 어긋남. 채택하지 않음.
- **(B) Maven Enforcer / Gradle plugin 기반 의존 차단** — 모듈 간 의존만 검사 가능. 단일 모듈 내 패키지 단위 계층 검증 표현력 부족. 채택하지 않음.
- **(C) 직접 `ClassFileTransformer`/ASM 룰** — 표현력은 충분하나 룰 작성/유지 비용이 ArchUnit 대비 명백히 크다. 차별점이 없는데 운영 부담만 큼. 채택하지 않음.
- **(D) Spring Modulith** — 모듈 경계 + 도메인 이벤트 검증 기능 포함. 매력적이나 의존성 추가 + 학습 비용이 ArchUnit보다 크고, 현재는 이벤트 사용처가 없어 과한 도입. ADR 0005 Alternatives (D)와 동일 사유로 v1 이후 별도 ADR 후보로 보류.

## References
- ADR 0005 — 패키지 구조 (본 ADR이 자동 검증 대상으로 삼는 룰의 원본).
- `docs/ai-harness/08-code-conventions.md §A-7` — 계층 의존 위반 예시.
- PR #89 — 패키지 구조 마이그레이션. `application → api.dto` 위반 8건이 발견·수정된 사례. 본 ADR의 직접 동기.
- PR #114 — be 사이클 8. ArchUnit 1.3.0 도입 + `LayerDependencyTest` 활성화 (본 ADR로 promote).
- `backend/src/test/java/com/mobruji/architecture/LayerDependencyTest.java` — 룰 본체.
- 후속 가능: cyclic dependency 금지, controller 어노테이션 검증, DTO 위치 검증 등은 같은 테스트 파일에서 룰 추가로 처리 (별도 ADR 없이).
