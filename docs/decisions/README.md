# Architecture Decision Records (ADR)

횡단 결정(도구/패턴/컨벤션)을 **짧고 영속적인** 레코드로 보존한다.

## Feature Spec과의 차이

| | Feature Spec | ADR |
|---|---|---|
| 단위 | 하나의 기능 | 하나의 결정 |
| 위치 | `docs/features/` | `docs/decisions/` |
| 생명 | 기능이 존재하는 동안 갱신 | **불변**. 번복 시 새 ADR 작성 |
| 예시 | "음역대 진단 기능 구현" | "Backend는 Spring Boot + Java 21로 간다" |

## 언제 ADR을 쓰는가

- 팀 전체에 영향을 주는 **컨벤션 결정** (네이밍, 디렉토리 구조, 라벨 체계)
- **도구/라이브러리 선택** (Spring Boot, Next.js, ML 라이브러리)
- **프로세스 결정** (Squash merge, Feature Spec 도입, Merge commit 릴리즈)
- **아키텍처 경계** (모노레포 분리, scope 화이트리스트)
- **정책 결정** (라이선스, 데이터 출처, 비용 상한)

## 언제 ADR을 쓰지 않는가

- 구체 기능 구현 → Feature Spec
- 단발 작업 지시 → 이슈 본문
- 임시 메모 → PR 코멘트

## 파일 네이밍

`NNNN-<slug>.md` — 4자리 일련번호 + 슬러그.

예시:
- `0001-tech-stack-and-monorepo.md`
- `0002-license-agpl-3-0.md`

일련번호는 순차. 삭제/재번호 금지.

### 분할 패턴 (같은 번호 + 보조 슬러그)

한 결정이 너무 커서 한 파일에 담기 어려우면 `NNNN-<slug>.md` + `NNNN-<slug>-<sub>.md` 형태로 분할할 수 있다. 동일 번호를 공유하되 슬러그 뒤에 보조 키워드를 붙여 역할을 구분한다.

예시:
- `0005-package-structure.md` — 최종 결정 (목표 구조 + 원칙)
- `archive/0005-package-structure-migration.md` — 부속 가이드 (마이그레이션 단계 / 일정) — 완료 후 archive 이동

분할은 예외 운용이며 기본은 단일 파일이다. 분할이 발생하면 메인 ADR의 References에 보조 문서를 명시한다.

### 부속 문서 (migration 가이드 등)

ADR 본문이 아닌 **부속 운영 가이드**(예: 1회성 마이그레이션 절차, 단계별 실행 체크리스트)는 다음 룰을 따른다.

- **위치**: 진행 중이면 `docs/decisions/`, 완료/대체되면 `docs/decisions/archive/` 로 이동. (대안: 작업성 가이드라면 `docs/migrations/` 별도 디렉토리 사용 가능 — 일관성 위해 한 레포 내 한 가지 선택을 권장)
- **명명**: ADR 번호 prefix 재사용 허용 (`NNNN-<slug>-<sub>.md`). 본 ADR과의 관계가 한눈에 보이도록.
- **frontmatter**: 본문 상단에 `status: companion to NNNN` 또는 `status: companion to NNNN — completed (archived YYYY-MM-DD, see #PR)` 명시.
- **라이프사이클**: 작업 완료 후 셀프 약속(superseded 표기 또는 archive 이동)을 즉시 이행. 본 ADR Repository 의 결정 이력 신뢰성을 위해 drift 금지.

## 라이프사이클

| Status | 의미 |
|---|---|
| `proposed` | 초안, 합의 전 |
| `accepted` | 합의 완료, 현재 적용 중 |
| `superseded by NNNN` | 다른 ADR에 의해 대체됨 (파일은 삭제하지 않음) |
| `deprecated` | 폐기, 더 이상 유효하지 않음 |

**중요**: 결정을 번복할 때는 기존 ADR을 수정하지 말고 **새 ADR을 작성**한 뒤 기존 것의 status를 `superseded by NNNN`으로 변경한다. 결정 이력 자체가 자산이다.

## 형식

`_template.md`를 복사해서 시작. 길이는 **50줄 이내** 권장.

섹션 순서 고정: Context → Decision → Consequences → Alternatives → References.

## 현재 목록

> **자동 갱신 의무 (2026-05-27 신설)**: 신규 ADR 추가 / status 전이 / superseded 표기 PR 은 같은 diff 안에서 본 표 행도 함께 갱신한다. drift 발견 시 plan 사이클이 docs(infra) sync PR 로 보강.
>
> 일련번호 오름차순. status = ADR 본문 frontmatter / Status 라인과 1:1.

| ADR | 제목 |
|---|---|
| [0001](0001-tech-stack-and-monorepo.md) | 기술 스택 및 모노레포 구조 채택 |
| [0002](0002-license-agpl-3-0.md) | 라이선스를 AGPL-3.0-or-later로 적용 |
| [0003](0003-test-db-strategy.md) | 테스트 DB 전략 — H2 (단위/슬라이스) + MySQL (통합/E2E) |
| [0004](0004-frontend-state-and-fetching.md) | 프론트엔드 상태 관리 및 데이터 페칭 스택 |
| [0005](0005-package-structure.md) | 패키지 구조 — BoundedContext × 4계층 (Hexagonal lite) |
| [0006](0006-audio-source-youtube.md) | 자체 곡 분석 파이프라인의 audio 출처 — YouTube audio extract |
| [0007](0007-vocal-difficulty-classification.md) | 곡 난이도 분류 — Difficulty enum (EASY / NORMAL / HARD) |
| [0008](0008-archunit-layer-verification.md) | ArchUnit 으로 계층 의존 자동 검증 (ADR-0005 §A-7 가드) |
| [0009](0009-schema-migration-tool.md) | Schema 마이그레이션 도구 = Flyway |
| [0010](0010-self-analysis-pipeline-stack.md) | 자체 곡 분석 파이프라인 실행 stack — Python worker + Spring ProcessBuilder |
| [0011](0011-session-bound-auth-policy.md) | Session-Bound Endpoint 인증 정책 |
| [0012](0012-observability-stack.md) | 운영 관측성 수집 스택 — Grafana Cloud Free + Prometheus remote_write |
| [0013](0013-sessionid-ttl-rotation.md) | 익명 sessionId TTL · 회전 · 데이터 라이프사이클 정책 |
| [0014](0014-multi-agent-worktree-orchestration.md) | 4 sub-agent 워크트리 + maestro 오케스트레이션 패턴 |
| [0015](0015-hosting-stack.md) | 운영 호스팅 스택 — NCP maestro 전용 VM (c2-g3a) + NCP 별 VM (백/프론트 기생) |
| [0016](0016-maestro-context-percent-estimation.md) | maestro context% 추정 방식 — `/context` slash + bot.py 5분 inject hybrid |
| [0017](0017-spring-boot-eol-strategy.md) | Spring Boot 3.5 EOL 대응 — 3.6 라인 채택 |
| [0018](0018-design-tokens.md) | Design tokens — color / typography / spacing / radius / shadow / motion |
| [0019](0019-event-driven-architecture-v2.md) | 작업 체계 event-driven 아키텍처 v2 — agent 망각 의존 폐기 |
| [0021](0021-worktree-count-evaluation.md) | 워크트리 개수 평가 — 4 워크트리 status quo 재확인 |
| [0022](0022-work-cycle-simplification-retrospective.md) | work-cycle-simplification 회고 (stub — Phase 3 완료 후 본문) |
| [0023](0023-workflow-main-sync.md) | workflow / unit file main 미동기화 사고 박제 + sync 전략 |
| [0024](0024-loop-heartbeat-reliability.md) | Loop heartbeat reliability — `try/finally` 단일 종점 record 패턴 |
| [0025](0025-directive-cleanup-option-b.md) | 잔존 directive 정리 — 옵션 B (분류 기반 자율 sweep) 채택 |
| [0026](0026-visual-regression-ci.md) | 다크모드·디자인 토큰 swap 회귀 가드 — Playwright visual regression CI 도입 (proposed) |
| [0027](0027-infra-dedicated-cycle-evaluation.md) | infra 전용 사이클 도입 평가 — 상시 워크트리 거부 + 온디맨드 infra 역할 채택 |
| [0028](0028-dev-https-tls-strategy.md) | dev 배포 HTTPS 전환 전략 — nip.io wildcard DNS + Let's Encrypt |
| [0029](0029-album-cover-art-source.md) | 앨범 커버 아트 출처 — iTunes Search 1차 + Cover Art Archive 폴백, Spotify 미채택 (proposed) |

> 번호 0020 은 비어 있음 (스킵). 0021 이 직후 번호.

### Archive

완료/대체된 부속 가이드.

| 파일 | 상태 |
|---|---|
| [0005-package-structure-migration](archive/0005-package-structure-migration.md) | ADR-0005 부속 마이그레이션 가이드 (완료 후 archive) |
