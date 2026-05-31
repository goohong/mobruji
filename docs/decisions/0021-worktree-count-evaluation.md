---
id: 0021
title: 워크트리 개수 평가 — 4 워크트리 status quo 재확인
status: accepted
date: 2026-05-26
deciders: [plan sub-agent (directive #1508005820539867236)]
related_issues: [1086]
related_directives: [1508005820539867236]
supersedes: []
superseded_by: []
---

# 0021. 워크트리 개수 평가 — 4 워크트리 status quo 재확인

## Context

Directive #1508005820539867236 ("워크트리 개수 단순화 + 메모리 학습 의존 폐기") 는 분석 결과 두 가지 독립 트랙으로 분할 처리하기로 결정되었다:

- **트랙 A — 메모리→코드 wiring**: 학습 의존 룰을 데몬/스크립트로 코드화. PR #1077 (ADR-0019 event-driven architecture v2 + Event-Action-Mapping + Work-Cycle-Refactor) 가 흡수.
- **트랙 B — 워크트리 개수 자체의 단순화**: be/fe/rev/plan 4 워크트리 구조 자체를 줄일지 평가. **본 ADR이 이 트랙을 영속 박제한다**.

ADR-0014 (multi-agent worktree orchestration, 2026-05-23) 가 5 워크트리 (maestro 1 + sub-agent 4) 구조를 default 로 고정한 이후, chain 1-5 실측 데이터로 사고 원인을 회고했을 때 워크트리 개수 자체가 chain 의 1차 원인이었던 사례는 0건이었다. 대부분의 사고는 (a) 메모리 학습 의존 (트랙 A 가 흡수), (b) jsonl ↔ Discord desync, (c) 채널 leak / target freeze 등 직교한 결함에서 발생했다.

따라서 트랙 B 의 결정은 "워크트리 개수를 줄여 simplify 하는 편이 사고를 줄이는가?" 라는 질문에 대한 **No** 답을 영속 박제하는 것이 본질이다. 단순화의 본질은 학습 의존 폐기 (트랙 A) 이지 워크트리 개수 축소가 아니다.

## Options 평가

### 옵션 A — 4 워크트리 status quo (be / fe / rev / plan)

- 작업 영역 boundary 가 패키지 경로와 1:1 (`backend/**` / `web/**` / `docs/**`) 자연 격리.
- chain 1-5 회고에서 워크트리 개수 자체가 사고 원인인 chain 0건.
- ADR-0014 에 이미 명문화된 default.
- **채택**.

### 옵션 B — 3 워크트리 (rev 흡수)

- rev sub-agent 를 be/fe 사이클의 후속 단계로 흡수 (rev 워크트리 폐기).
- 거부 사유:
  - rev 2단계 e2e (🟡 Pre-merge review / 🔵 Post-merge audit, 단계 3 폐기 2026-05-30) 는 LLM reasoning + LGTM drift self-guard 가 본질 (`docs/features/rev-e2e-2-stages.md`, `[[feedback-rev-e2e-always]]`). 같은 워크트리에서 self-review 시 작업자 편향 → drift 박제.
  - ADR-0020 (예약, work-cycle-refactor 회고) §5-5 측정값 (후속/회귀/drift 32% → < 15%) **미달성**. rev 흡수는 측정값 달성 후에만 재평가 가능.
  - rev 큐 (`tools/rev-queue/`) 가 매 사이클 첫 액션을 강제 — rev 워크트리 폐기 시 큐 의무화도 사라져 audit 누락 risk.
- **거부 (재평가 의무)**.

### 옵션 C — 2 워크트리 (be + fe 통합)

- backend / web 를 하나의 워크트리에 통합.
- 거부 사유:
  - fe 워크트리는 `npm install` 시 `node_modules` symlink 가 풀려 즉시 swap 복구가 필요 (`[[feedback-npm-install-symlink-swap]]`). 통합 시 be sub-agent 가 backend 작업 도중 fe 의 symlink 상태를 보존하기 위한 추가 룰 필요 — 단순화가 아니라 복잡화.
  - be/fe 사이클 독립 병행 (`[[feedback-be-fe-parallel]]`) 으로 placeholder fallback 만으로 거의 동시 launch 가능 — 통합 시 git stash/checkout 경합 발생.
  - 패키지 경로 boundary 1:1 매칭 깨짐 → PR scope 와 워크트리 경로 일치 룰 위반.
- **거부**.

### 옵션 D — 1 워크트리 + 라벨 (논리적 분리만)

- 모든 sub-agent 가 maestro 워크트리에서 작업, scope 라벨로 논리 분리.
- 거부 사유:
  - 워크트리 lock (`[[feedback-worktree-lock]]`) 의 git stash/checkout race 방어가 사라짐 — sub-agent 1개만 가동 가능 → wall-clock 4배 증가.
  - npm install symlink swap 사고 risk 가 매 사이클 노출.
  - "4 워크트리 항시 가동" 룰 (`[[feedback-keep-4-cycles-active]]`) 의 throughput 보장 메커니즘 (병렬 4 sub-agent) 폐기.
  - "단순화" 라기보다 "쇠퇴" — 자율 사이클 메커니즘 자체가 무너짐.
- **거부**.

## 옵션 비교 매트릭스

| 옵션 | 워크트리 수 | npm symlink 보호 | git race 보호 | rev e2e drift 방어 | wall-clock (4 사이클 동시) | 채택 |
|---|---|---|---|---|---|---|
| A — status quo | 5 (maestro 포함) | O (fe 격리) | O (워크트리 lock) | O (rev 독립) | 1x | 채택 |
| B — rev 흡수 | 4 | O | O | X (self-review drift) | 1.2x | 거부 (ADR-0020 측정 후 재평가) |
| C — be+fe 통합 | 3 | X (symlink 위험) | △ (be/fe race 부활) | O | 1.5x | 거부 |
| D — 1 워크트리 | 2 (maestro + 통합) | X | X | X | 4x | 거부 |

## Decision

**옵션 A — 4 워크트리 status quo 를 default 로 유지**한다.

사유:
1. directive "단순화" 의 본질은 **메모리 학습 의존 폐기** (트랙 A, PR #1077 흡수) 이지 워크트리 개수 축소가 아니다.
2. chain 1-5 회고에서 워크트리 개수가 사고 원인인 chain 0건 — 줄여서 얻을 사고 감소 효과 부재.
3. 옵션 B/C/D 는 모두 안전망 (npm symlink swap / git race / rev drift) 1개 이상 해체 → "단순화" 가 아니라 "사고 risk 증가".

본 ADR 은 ADR-0014 결정을 재확인하면서, 향후 같은 질문이 재발할 때 옵션 B/C/D 거부 사유를 영속 박제하는 것이 1차 가치다.

## Consequences

### 긍정적

- ADR-0014 + 본 ADR 두 결정으로 워크트리 개수 변경 의사결정 root 가 명확.
- 옵션 B/C/D 의 거부 사유가 영속 박제되어 재논의 시 같은 분석 재수행 불필요.
- 트랙 A 와 트랙 B 의 책임 분리가 명확 — 메모리→코드 wiring (트랙 A) 과 워크트리 구조 (트랙 B) 가 독립 결정.

### 부정적

- 4 워크트리 status quo 의 단점 (maestro 세션 종료 시 sub-agent 일제 종료, 4 워크트리 메모리 race 위험) 은 ADR-0014 §Consequences 에 이미 명시된 그대로 잔존.
- ADR-0020 측정값이 달성되면 옵션 B 재평가 의무 — 본 ADR 의 결정이 영구는 아님.

### 재평가 의무 (조건부 trigger)

| trigger | 재평가 대상 옵션 | 측정 source |
|---|---|---|
| ADR-0020 §5-5 후속/회귀/drift 32% → < 15% 달성 | 옵션 B (rev 흡수) | `docs/features/work-cycle-refactor.md §5-5` 회고 |
| 워크트리 개수 원인 chain 1건 이상 발생 | 옵션 A 자체 재평가 | chain 회고 (메모리 `project_session_handoff_*`) |
| be/fe placeholder fallback 룰 폐기 | 옵션 C (be+fe 통합) | `[[feedback-be-fe-parallel]]` 변경 시 |

trigger 발생 시 plan sub-agent 가 본 ADR 의 superseded_by 필드 갱신 + 신규 ADR 작성.

## Alternatives (considered)

- **(A) 4 워크트리 status quo** — 채택. chain 0건 + 안전망 모두 보존.
- **(B) 3 워크트리 (rev 흡수)** — 거부 (ADR-0020 측정 후 재평가). rev e2e LLM reasoning + LGTM drift self-guard 가 본질.
- **(C) 2 워크트리 (be+fe 통합)** — 거부. npm symlink swap 사고 risk + be/fe 사이클 독립 병행 룰 위반.
- **(D) 1 워크트리 + 라벨** — 거부. 워크트리 lock / 4 사이클 항시 가동 메커니즘 해체.

## References

- Directive: #1508005820539867236 (워크트리 개수 단순화 + 메모리 학습 의존 폐기)
- ADR-0014: `docs/decisions/0014-multi-agent-worktree-orchestration.md` (5 워크트리 default 결정)
- ADR-0019: `docs/decisions/0019-event-driven-architecture-v2.md` (트랙 A 흡수, PR #1077 진행 중)
- ADR-0020: work-cycle-refactor 회고 (예약, 옵션 B 재평가 trigger)
- PR #1077: docs(infra) architect 통합 — fresh-start ADR + Event-Action-Mapping + Work-Cycle-Refactor (#1074)
- `docs/features/work-cycle-refactor.md` §5-5 — 회고 측정값
- `docs/features/event-action-mapping.md` — 트랙 A 코드 wiring 스펙
- 메모리: `[[feedback-npm-install-symlink-swap]]`, `[[feedback-worktree-lock]]`, `[[feedback-keep-4-cycles-active]]`, `[[feedback-be-fe-parallel]]`, `[[feedback-rev-e2e-always]]`
- 관련 이슈: #1086

## 변경 이력

- 2026-05-26 — plan sub-agent 최초 작성 (directive #1508005820539867236 트랙 B 영속 박제).
