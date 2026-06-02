---
id: 0027
title: infra 전용 사이클 도입 평가 — 상시 워크트리 거부 + 온디맨드 infra 역할 채택
status: accepted
date: 2026-06-03
deciders: [plan sub-agent (directive roadmap-infra-agent-adr)]
related_issues: [1453]
related_directives: [roadmap-infra-agent-adr]
supersedes: []
superseded_by: []
---

# 0027. infra 전용 사이클 도입 평가 — 상시 워크트리 거부 + 온디맨드 infra 역할 채택

## Context

밤샘 자율 운행에서 인프라 변경 — `.github/workflows/**`, `tools/**`, `bot.py` (mobruji-bridge), 에이전트 launch 스크립트 — 이 다수 발생했다. 현재 이 작업은 be 사이클에 혼용되거나 mmae 가 직접 처리한다. 둘 다 결함이 있다:

- **be 혼용**: be 워크트리 boundary 는 `backend/**` 로 고정 (`actors/sub-agent.md §2-be`). `.github`/`tools`/`bot.py` 는 be 의 허용 경로 **밖** 이라 워크트리 격리 룰 위반. be 가 infra 를 건드리면 PR scope ↔ 패키지 경로 일치 룰도 깨진다.
- **mmae 직접 처리**: ADR-0014 §4 "maestro 작업 default = 메타" 위반. mmae 가 직접 코드를 쓰면 5 워크트리 중 하나가 idle 이 되고 오케스트레이션 컨텍스트가 오염된다.

게다가 `scope:infra` 는 PR scope 화이트리스트(`02-agent-workflow.md`)와 Project 보드 Session 값(option-id `c697cb09`)으로 **이미 존재**하고, `auto-set-session-on-project.yml` 이 `scope:infra → infra` Session 으로 자동 분류한다 (`11-multi-session-runbook.md §486`). 그러나 실제 실행 워크트리/sub-agent 는 없다 — **보드 분류는 있는데 실행 주체가 없는 불일치**가 본 ADR 의 발화점이다.

따라서 결정해야 할 질문: "infra 작업에 전용 사이클(워크트리+role+라우팅)을 신설할 것인가?"

## 제약 조건 (결정의 1차 입력)

1. **호스트 리소스 상한**: maestro 호스트 = NCP c2-g3a (2vCPU/4GB, ADR-0015). 현재 maestro 1 + sub-agent 4 (be/fe/rev/plan) + bot.py + Discord daemon 이 상주해 ADR-0015 가 "약간의 여유 동시 수용" 으로 표현한 빠듯한 헤드룸이다. 상시 가동 6번째 세션은 2vCPU 경합 + 메모리(#1453 실측 여유 부족) thrash risk.
2. **워크로드 burstiness**: infra 변경은 밤샘 같은 특정 구간에 몰리는 bursty 패턴이지, be/fe/rev/plan 처럼 연속 백로그가 아니다. "항시 4 사이클 가동" 룰(`[[feedback-keep-4-cycles-active]]`)에 5번째를 더하면 대부분 시간 idle → 리소스 점유만 하고 가치 없음.
3. **두 repo span**: infra 표면이 `mobruji` repo(`.github`, `tools`)와 `mobruji-bridge` repo(`bot.py`, discord-daemon)에 걸친다. 단일 워크트리는 한 repo 만 담는다(`[[feedback-worktree-lock]]` 격리). 전용 사이클이 두 repo 를 모두 다루려면 워크트리 2개 또는 repo-hopping 이 필요 — 격리 룰과 충돌.
4. **라우팅 wiring blast radius**: 5번째 사이클은 `agent-launch-wrapper.sh` role 매핑 / `00-MANIFEST.md` / `cycle-status.json` 5번째 slot / forum `INFRA_CHANNEL_ID` / nmae 라우팅 / `sub-agent.md §2-infra` 를 모두 건드린다 — bursty 작업 치고 변경 범위가 크다.

## Options 평가

### 옵션 A — 신규 상시 infra 사이클 (전용 워크트리 + role + nmae 라우팅)

- `mobruji-infra` 워크트리 신설 + 5번째 항시 가동 사이클로 승격.
- 거부 사유:
  - 제약 1 (리소스): 2vCPU/4GB 에 6번째 상시 세션 → OOM/thrash risk.
  - 제약 2 (bursty): 항시 가동인데 백로그는 간헐적 → 대부분 idle, 리소스만 점유.
  - 제약 3 (두 repo): 단일 워크트리가 `.github`+`tools`(mobruji)와 `bot.py`(bridge)를 동시에 담을 수 없음. 두 워크트리로 쪼개면 비용 2배.
- **거부 (리소스 + bursty + 두 repo span)**.

### 옵션 B — be 사이클 재사용 (be 가 infra 도 담당)

- be 워크트리 boundary 를 `backend/**` 에서 `.github`/`tools` 까지 확장.
- 거부 사유:
  - be 의 경로 boundary 가 `backend/**` 와 1:1 (DDD 패키지 격리) — 확장 시 PR scope ↔ 경로 일치 룰 붕괴.
  - infra 변경(workflow YAML, shell)과 backend 도메인 코드가 한 워크트리에 섞이면 rev 의 scope 기반 review 매트릭스가 흐려짐.
  - `bot.py`(bridge repo)는 be 워크트리(`mobruji-be`, mobruji repo)에서 접근 불가 — 본질적 미스매치.
- **거부 (경로 boundary 미스매치 + 두 repo 미해결)**.

### 옵션 C — 현행 유지 (be 혼용 또는 mmae 직접)

- 현 상태 그대로.
- 거부 사유:
  - Context 에 적은 두 결함(be 격리 위반 / mmae 메타 default 위반)이 영속.
  - `scope:infra → infra` 보드 분류는 있는데 실행 주체가 없는 불일치 미해소.
- **부분 거부 (현 분산은 유지하되 owner 명문화 필요)**.

### 옵션 D — 온디맨드 infra 역할 (상시 워크트리 X, ephemeral 워크트리 launch) — **채택**

- infra 를 **항시 가동 5번째 사이클이 아니라 온디맨드 sub-agent 역할**로 도입. infra 백로그가 누적되면 nmae 가 기존 helper-launched ephemeral 워크트리 메커니즘(`/home/mobruji/mobruji/.claude/worktrees/agent-<id>`)으로 `infra` role sub-agent 를 launch, 작업 후 워크트리 teardown.
- 범위 분할 (두 repo span 해소):
  - **mobruji repo infra** (`.github/workflows/**`, `tools/**` 중 비-docs, root 설정) → 온디맨드 `infra` role.
  - **`docs/ai-harness/**` / `scripts/**` / `.github` 중 docs 성격** → 기존 plan 사이클(이미 `.github/**`+`scripts/**` 허용, `§2-plan`).
  - **`bot.py` / discord-daemon (mobruji-bridge repo, 운영성)** → mmae 또는 helper-launched sub-agent (별 repo + 운영 hot path 라 신중도 가중).
- 채택 사유:
  - 제약 1 (리소스): idle 시 0 세션 → 6번째 상시 세션 비용 회피. 6번째 세션은 infra 백로그 있을 때만 일시 점유.
  - 제약 2 (bursty): 온디맨드 = burstiness 와 정확히 일치. 백로그 고갈 시 자동 0.
  - 제약 3 (두 repo): repo 별 owner 명시 분할로 단일 워크트리가 한 repo 만 담는 격리 룰 보존.
  - 제약 4 (wiring): ephemeral 워크트리 메커니즘은 helper-launched 로 **이미 존재** — `cycle-status.json` 5번째 slot / forum 채널 / 항시 가동 룰 변경 불필요. 신규 wiring 은 `sub-agent.md §2-infra` 역할 정의 + nmae 온디맨드 launch 판단 룰뿐.
  - rev 영향: infra PR 도 다른 PR 과 동일하게 rev 통과 의무(`[[feedback-rev-e2e-always]]`) — rev 부하 변화 없음. 오히려 owner 명시로 보호 영역 변경 PR 의 rev 신중도 가중이 더 일관됨.

## 옵션 비교 매트릭스

| 옵션 | 상시 리소스 비용 | 경로 boundary 적합 | 두 repo span 처리 | 라우팅 wiring 비용 | rev 영향 | 채택 |
|---|---|---|---|---|---|---|
| A — 상시 infra 워크트리 | 높음 (6번째 상시 세션) | O | X (단일 워크트리 불가) | 높음 (5 slot 신설) | 중립 | 거부 |
| B — be 재사용 | 0 (기존) | X (backend/** 미스매치) | X (bridge 접근 불가) | 낮음 | 흐려짐 | 거부 |
| C — 현행 (be 혼용/mmae) | 0 | X (혼용 위반) | △ (분산되나 미명문) | 0 | 중립 | 부분 거부 |
| D — 온디맨드 infra 역할 | idle 0 / 작업 시만 점유 | O (ephemeral 격리) | O (repo 별 owner 분할) | 낮음 (ephemeral 기존 재사용) | 중립 (owner 명시 ↑) | **채택** |

## Decision

**옵션 D — 온디맨드 `infra` sub-agent 역할을 도입한다. 상시 가동 5번째 워크트리(옵션 A)는 거부한다.**

1. infra 작업은 **항시 가동 사이클이 아니라 온디맨드 역할**로 처리한다. nmae 가 infra 백로그 누적 시 helper-launched ephemeral 워크트리로 `infra` role sub-agent 를 launch 한다.
2. infra 표면을 repo 별 owner 로 분할한다: mobruji-repo infra(`.github`/`tools`) = 온디맨드 `infra` 역할 / `bot.py`·bridge 운영성 = mmae 또는 helper-launched / docs 성격 infra = plan.
3. `scope:infra` 라벨/보드 Session 분류는 그대로 유지하되, 실행 주체가 온디맨드 `infra` 역할임을 명문화한다.

사유 핵심: 2vCPU/4GB 호스트(제약 1) + infra 워크로드의 bursty 성격(제약 2) 하에서 상시 6번째 세션은 리소스 비용 대비 가치가 없고, infra 표면이 두 repo 에 걸쳐(제약 3) 단일 전용 워크트리로 담기지 않는다. 온디맨드 역할은 idle 시 0 비용 + 기존 ephemeral 메커니즘 재사용으로 wiring blast radius 가 최소(제약 4)다.

## Consequences

### 긍정적

- infra 작업의 owner 가 명문화됨 — be 격리 위반 / mmae 메타 default 위반(옵션 C 결함) 해소.
- `scope:infra → infra` 보드 분류와 실행 주체의 불일치 해소.
- idle 시 0 세션 — 2vCPU/4GB 헤드룸 보존, 상시 6세션 OOM risk 회피.
- 두 repo span 을 repo 별 owner 분할로 해소 — 워크트리 격리 룰 보존.
- 신규 wiring 최소 (ephemeral 메커니즘 재사용) — `cycle-status.json` 4 slot / 항시 가동 룰 / forum 채널 구조 불변.

### 부정적

- 온디맨드 launch 판단(언제 infra 백로그가 "누적" 인가)이 nmae 의 추가 판단 부하 — 임계치 룰을 후속 spec 으로 명문화 필요.
- repo 별 owner 분할이 경계 케이스(예: workflow 가 bot.py 를 호출하는 변경)에서 두 owner 협업을 요구할 수 있음.
- ephemeral 워크트리 teardown 누락 시 디스크 잔존 — helper-launched 와 동일한 정리 룰 적용 필요.

### 후속 작업 (별도 PR — 본 ADR 은 결정만, 코드/룰 wiring 은 후속)

- `actors/sub-agent.md §2-infra` 역할 정의 추가 (허용 경로 = mobruji repo `.github`/`tools`/root 설정, 금지 = `backend/**`/`web/**`/bridge repo).
- `00-MANIFEST.md` 에 온디맨드 infra 역할 라우팅 1줄.
- nmae 온디맨드 launch 임계치 룰 (infra 백로그 N건 누적 또는 보호 영역 변경 PR 발의 시) — `actors/nmae.md` 또는 후속 spec.

### 재평가 의무 (조건부 trigger)

| trigger | 재평가 대상 | 측정 source |
|---|---|---|
| infra PR 주간 volume 이 지속적으로 높음 (예: 4주 연속 주 10건+) | 옵션 A (상시 워크트리 승격) | PR 라벨 `scope:infra` 집계 |
| 호스트 업그레이드 (vCPU/RAM 증설) | 옵션 A (리소스 제약 해소) | ADR-0015 supersede 시 |
| 온디맨드 launch 판단이 반복 누락/지연 | 옵션 D 임계치 룰 강화 또는 상시화 | nmae 사이클 회고 |

trigger 발생 시 plan 사이클이 본 ADR superseded_by 갱신 + 신규 ADR 작성.

## Alternatives (considered)

- **(A) 신규 상시 infra 워크트리** — 거부. 2vCPU/4GB 6세션 risk + bursty idle + 두 repo 단일 워크트리 불가.
- **(B) be 재사용** — 거부. be `backend/**` 경로 boundary 미스매치 + bridge repo 접근 불가.
- **(C) 현행 (be 혼용/mmae 직접)** — 부분 거부. owner 미명문 + 보드↔실행 불일치 미해소.
- **(D) 온디맨드 infra 역할** — 채택. idle 0 비용 + repo 별 owner 분할 + ephemeral 메커니즘 재사용.

## References

- Directive: roadmap-infra-agent-adr (infra 전용 사이클 도입 검토)
- ADR-0014: `docs/decisions/0014-multi-agent-worktree-orchestration.md` (4 sub-agent 워크트리 + maestro, §4 메타 default)
- ADR-0015: `docs/decisions/0015-hosting-stack.md` (NCP c2-g3a 2vCPU/4GB 호스트 + "약간의 여유" 헤드룸)
- ADR-0021: `docs/decisions/0021-worktree-count-evaluation.md` (워크트리 개수 평가 — 항시 가동 4 워크트리 status quo)
- `docs/ai-harness/11-multi-session-runbook.md §478-486` — `scope:infra → infra` Session 자동 분류 (실행 주체 부재)
- `docs/ai-harness/actors/sub-agent.md §1-1, §2-be, §2-plan` — 워크트리 격리 + 역할별 허용 경로
- `docs/ai-harness/02-agent-workflow.md` — scope 화이트리스트 (`infra` 포함)
- 메모리: `[[feedback-worktree-lock]]`, `[[feedback-keep-4-cycles-active]]`, `[[feedback-rev-e2e-always]]`
- 관련 이슈: #1453 (NCP 2vCPU 리소스 여유 제약)

## 변경 이력

- 2026-06-03 — plan sub-agent 최초 작성 (directive roadmap-infra-agent-adr — 상시 워크트리 거부 + 온디맨드 infra 역할 채택 박제).
