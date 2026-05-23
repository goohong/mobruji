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
- `0005-package-structure-migration.md` — 부속 결정 (마이그레이션 단계 / 일정)

분할은 예외 운용이며 기본은 단일 파일이다. 분할이 발생하면 메인 ADR의 References에 보조 ADR을 명시한다.

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
