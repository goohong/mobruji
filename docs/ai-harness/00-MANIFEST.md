# AI Harness Volume 0: MANIFEST & ROUTING

> nmae(NCP maestro)가 sub-agent 를 `Agent` 도구로 launch 할 때, 역할에 맞는 컨텍스트(하네스 문서 + 프롬프트 템플릿)를 주입하기 위한 **라우팅 지도**입니다.
>
> 본 문서는 기존 16개 하네스 문서를 **대체하지 않습니다**. 어떤 문서를 어떤 역할에 주입할지 + 어떤 트랙으로 작업할지를 한눈에 모으는 인덱스입니다. 상세·단일 진실(SoT)은 각 문서가 그대로 보유합니다.

---

## 1. 주제별 문서 그룹 (어디서 뭘 찾나)

문서를 주제로 묶은 네비게이션 (파일명/번호는 그대로 — 교차 참조 보존). 아래 §2 가 이 그룹명으로 역할별 주입을 지정한다.

| 그룹 | 주제 | 문서 |
|---|---|---|
| **Governance** | 브랜치/PR/커밋/품질게이트/보안/Spec 프로세스 | `01-harness-spec`, `02-agent-workflow`, `03-quality-gates`, `04-security-policy` |
| **Architect** | 도메인 모델/유비쿼터스 랭귀지/ERD/설계 결정 | `06-domain-model`, `docs/decisions/*` (ADR) |
| **Standards** | 코드 컨벤션/테스트/관측성/프롬프트 운영 | `08-code-conventions`, `07-testing-guide`, `10-observability`, `05-prompt-ops` |
| **Ops** | 세션/오케스트레이션/메모리/Discord/룰 강제 | `11-multi-session-runbook`, `actors/sub-agent`, `13-memory-and-enforcement`, `14-discord-ops`, `actors/nmae`, `docs/ai-harness/actors/helper.md` |

## 2. 에이전트별 주입 맵 (Injection Map)

nmae 는 sub-agent launch 시 역할에 따라 아래 문서만 발췌 주입 (토큰 절약).

| 역할 | 워크트리 | 필수 주입 | 보조 |
|---|---|---|---|
| **nmae** (오케스트레이터) | NCP `mobruji:0.0` | `actors/nmae.md` + CLAUDE.md §0~§10,§14,§16,§17 | Governance |
| **Plan** | `mobruji-plan` | Governance, Architect | Standards (형식 참조) |
| **BE** | `mobruji-be` | Governance, Standards, `actors/sub-agent §2 be` | Architect (BC/엔티티) |
| **FE** | `mobruji-fe` | Governance, Standards, `actors/sub-agent §2 fe` | - |
| **Rev** | `mobruji-rev` | Governance, Standards, `rev-e2e-2-stages.md` | Architect (설계 의도) |
| **Helper** | (mac `helper:0.0`) | `docs/ai-harness/actors/helper.md` (SoT) | Governance (말투) |
| **Infra** (온디맨드) | ephemeral `agent-<id>` | Governance, `actors/sub-agent §2-infra` | Ops (tools/workflow) |

> sub-agent 는 `docs/ai-harness/actors/sub-agent.md` 한 개만 로드하면 공통+역할 룰이 모두 들어옵니다 (CLAUDE.md §13 포인터와 동일).
>
> **infra 는 상시 가동 아님 (ADR-0027 옵션 D)**: be/fe/rev/plan 4 사이클과 달리 자율 엔진 dispatcher `CYCLES=(be,fe,rev,plan)` 에 미포함 — nmae/mmae 가 infra 백로그 누적 또는 보호 영역 변경 PR 발의 시 ephemeral 워크트리로 온디맨드 launch (자동 dispatch 미지원, 자율 self-dispatch 는 엔진 지원 후속 이슈). 임계치 룰 = `actors/nmae.md §11-9`.

## 3. 작업 트랙 (Triage)

nmae 가 백로그/지시를 분류해 트랙을 결정합니다. 실제 사이클 운영(directive-board / event-driven)은 ADR `0019-event-driven-architecture-v2.md` + `docs/features/work-cycle-refactor.md` 가 SoT.

### A. Standard Track (설계 우선)
- **조건**: 신규 도메인, 외부 연동, 다중 PR, 복잡 비즈니스 로직.
- **흐름**: `Plan`(Feature Spec/ADR) → 합의 → `BE`/`FE`(구현) → `Rev`(2단계 e2e 검증 — 🟡 Pre-merge review / 🔵 Post-merge audit).

### B. Fast Track (구현 우선)
- **조건**: 명확한 버그 수정, 단순 UI/텍스트 변경, 파일 변경 < 3개, 기존 패턴 반복.
- **흐름**: `BE`/`FE`(즉시 수정) → `Rev`(검증). Spec 생략 가능.

## 4. 에이전트 통신 계약 (command IN / report OUT)

> 에이전트 간 지시·보고의 **고정 규격**. 별도 템플릿 파일을 두지 않는다 — 규격은 **실제 launch 시 주입되는 SoT** 에만 둬야 지켜진다 (문서로 두면 스킵됨, `13-memory-and-enforcement.md` 철학).

**① 지시 IN (nmae → sub-agent)** — 강제: `tools/agent-launch-wrapper.sh` 가 set-active + 채널 알림 + (옵션) `--echo-prompt` 으로 launch 본문 emit. 필수 필드:
`목표/이슈` · `워크트리(mobruji-<role>)` · `참조 Spec(또는 Fast Track 명시)` · `완료 기준(DoD)`. 역할별 상세 = `actors/sub-agent.md §2`.

**② 보고 OUT (sub-agent → nmae)** — 강제: `actors/sub-agent.md §1` (launch 시 sub-agent 가 로드). 필수 필드:
`PR URL` · `mergeable` · `품질 게이트 결과` · `보호 영역 여부` · **발견 사항(🔴/🟡/🟢)** · `다음 사이클 후보`.

**③ 상태 공유 (서로)** — `cycle-status.json` (`tools/cycle-status/update.sh`) + `directive-board.jsonl` (`~/.mobruji/directive_*.sh`). 수동 편집 금지 — 헬퍼만.

## 5. 핸드오프 보고 프로토콜

모든 sub-agent 는 turn 종료 시 nmae 에게 표준 양식으로 보고 (`01-harness-spec` / CLAUDE.md §13-1):
`PR URL` · `변경 요약` · `품질 게이트 결과` · `보호 영역 여부` · **발견 사항(🔴/🟡/🟢)** · `다음 사이클 후보`.

nmae 는 🔴(시급) 발견 사항을 같은 사이클에 즉시 후속 트리거, 🟡/🟢 는 백로그 등록 (CLAUDE.md §11-7).

---
**SSOT 원칙**: 본 MANIFEST 와 개별 문서 내용이 충돌하면 **개별 문서(및 CLAUDE.md 본문)가 우선**입니다. 본 문서는 네비게이션/주입 인덱스이며 룰의 원천이 아닙니다.
