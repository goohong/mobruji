# AI Harness Volume 0: MANIFEST & ROUTING

> nmae(NCP maestro)가 sub-agent 를 `Agent` 도구로 launch 할 때, 역할에 맞는 컨텍스트(하네스 문서 + 프롬프트 템플릿)를 주입하기 위한 **라우팅 지도**입니다.
>
> 본 문서는 기존 16개 하네스 문서를 **대체하지 않습니다**. 어떤 문서를 어떤 역할에 주입할지 + 어떤 트랙으로 작업할지를 한눈에 모으는 인덱스입니다. 상세·단일 진실(SoT)은 각 문서가 그대로 보유합니다.

---

## 1. 문서 → 4 볼륨 그룹 (Navigation)

16개 평면 문서를 주제별 4 그룹으로 묶은 view (파일명/번호는 그대로 유지 — 교차 참조 보존).

| 볼륨 | 주제 | 포함 문서 |
|---|---|---|
| **Vol 1 · Governance** | 브랜치/PR/커밋/품질게이트/보안/Spec 프로세스 | `01-harness-spec`, `02-agent-workflow`, `03-quality-gates`, `04-security-policy` |
| **Vol 2 · Architect** | 도메인 모델/유비쿼터스 랭귀지/ERD/설계 결정 | `06-domain-model`, `docs/decisions/*` (ADR) |
| **Vol 3 · Standards** | 코드 컨벤션/테스트/관측성/프롬프트 운영 | `08-code-conventions`, `07-testing-guide`, `10-observability`, `05-prompt-ops` |
| **Vol 4 · Ops Runbook** | 세션/오케스트레이션/메모리/Discord/룰 강제 | `11-multi-session-runbook`, `12-sub-agent-prompt-template`, `13-memory-promote-tracking`, `14-discord-notify-setup`, `15-discord-message-templates`, `16-memory-vs-code-enforcement`, `docs/helper-rules.md` |

## 2. 에이전트별 주입 맵 (Injection Map)

nmae 는 sub-agent launch 시 역할에 따라 아래 문서만 발췌 주입 (토큰 절약).

| 역할 | 워크트리 | 필수 주입 | 보조 |
|---|---|---|---|
| **Plan** | `mobruji-plan` | Vol 1, Vol 2 | Vol 3 (형식 참조) |
| **BE** | `mobruji-be` | Vol 1, Vol 3, `12-sub-agent-prompt-template §2 be` | Vol 2 (BC/엔티티) |
| **FE** | `mobruji-fe` | Vol 1, Vol 3, `12-sub-agent-prompt-template §2 fe` | - |
| **Rev** | `mobruji-rev` | Vol 1, Vol 3, `rev-e2e-3-stages.md` | Vol 2 (설계 의도) |
| **Helper** | (mac `helper:0.0`) | `docs/helper-rules.md` (SoT), CLAUDE.md §12 | Vol 1 (말투) |

> sub-agent 는 `docs/ai-harness/12-sub-agent-prompt-template.md` 한 개만 로드하면 공통+역할 룰이 모두 들어옵니다 (CLAUDE.md §13 포인터와 동일).

## 3. 작업 트랙 (Triage)

nmae 가 백로그/지시를 분류해 트랙을 결정합니다. 실제 사이클 운영(directive-board / event-driven)은 ADR `0019-event-driven-architecture-v2.md` + `docs/features/work-cycle-refactor.md` 가 SoT.

### A. Standard Track (설계 우선)
- **조건**: 신규 도메인, 외부 연동, 다중 PR, 복잡 비즈니스 로직.
- **흐름**: `Plan`(Feature Spec/ADR) → 합의 → `BE`/`FE`(구현) → `Rev`(3단계 e2e 검증).

### B. Fast Track (구현 우선)
- **조건**: 명확한 버그 수정, 단순 UI/텍스트 변경, 파일 변경 < 3개, 기존 패턴 반복.
- **흐름**: `BE`/`FE`(즉시 수정) → `Rev`(검증). Spec 생략 가능.

## 4. 작업 템플릿 (prompts/)

nmae/helper 가 sub-agent launch 시 prompt 본문에 사용하는 재사용 템플릿. 운영 규칙은 `05-prompt-ops.md`.

| 템플릿 | 용도 | 대상 |
|---|---|---|
| `prompts/feature-implementation.md` | 기능 구현/리팩터 위임 | BE / FE / Plan |
| `prompts/review-qa.md` | PR 사후 감사/QA | Rev |

## 5. 핸드오프 보고 프로토콜

모든 sub-agent 는 turn 종료 시 nmae 에게 표준 양식으로 보고 (`01-harness-spec` / CLAUDE.md §13-1):
`PR URL` · `변경 요약` · `품질 게이트 결과` · `보호 영역 여부` · **발견 사항(🔴/🟡/🟢)** · `다음 사이클 후보`.

nmae 는 🔴(시급) 발견 사항을 같은 사이클에 즉시 후속 트리거, 🟡/🟢 는 백로그 등록 (CLAUDE.md §11-7).

---
**SSOT 원칙**: 본 MANIFEST 와 개별 문서 내용이 충돌하면 **개별 문서(및 CLAUDE.md 본문)가 우선**입니다. 본 문서는 네비게이션/주입 인덱스이며 룰의 원천이 아닙니다.
