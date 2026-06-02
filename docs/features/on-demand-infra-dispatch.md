---
feature: 온디맨드 infra dispatch — 자율 엔진 ephemeral 워크트리 launch 경로
slug: on-demand-infra-dispatch
status: draft
owner: @mobruji-maestro
scope: infra
related_issues: [1531, 1453]
related_prs: []
last_reviewed: 2026-06-03
---

# 온디맨드 infra dispatch — 자율 엔진 ephemeral 워크트리 launch 경로

## 1) 개요 (What / Why)
ADR-0027 이 **옵션 D(온디맨드 infra 역할)** 를 채택했고, `sub-agent.md §2-infra` 가 역할(허용 경로/금지/품질게이트)을 정의했다. 그러나 자율 NCP 엔진(`tools/agent/`)의 dispatcher 는 `CYCLES=(be,fe,rev,plan)` 4종만 순회하고 `work_queue.VALID_CYCLES` 도 4종만 허용한다 — **infra 역할이 정의는 됐는데 엔진이 자동 dispatch 하지 못하는 갭**이 남았다. 결과적으로 infra directive 는 enqueue 단계에서 `ValueError("invalid cycle: infra")` 로 거부되고, nmae/mmae 의 수동 `Agent` 도구 launch 에만 의존한다.

본 spec 은 이 갭을 닫는 **구현 방법을 비교·결정**한다 (코드는 후속 PR — 본 spec 은 설계만). 핵심 선택은 옵션 (a) ephemeral 워크트리 launch 경로 vs 옵션 (b) 전용 infra 워크트리 1개다. 이 갭이 풀리면 #1524 구현 / #1449 테스트 부채 / dispatcher ValueError fix 등 인프라 코드가 자율 큐로 처리된다.

**대상 액터**: 자율 엔진 dispatcher (`tools_queue.dispatch_once`), `subagent_runner` (실제 실행 엔진), nmae (cycle_hint 라우팅), rev (infra PR 통과 의무), 오너 (Discord observer).

## 2) 용어 / 약어
| 용어 | 의미 |
|---|---|
| **dispatcher** | `tools/agent/tools_queue.dispatch_once` — agent_loop tick 마다 각 cycle 큐 idle 시 다음 항목 launch |
| **standing 워크트리** | 상시 존재하는 4 워크트리 (`mobruji-be`/`-fe`/`-rev`/`-plan`) — 한 워크트리 = 동시 1 sub-agent ([[feedback-worktree-lock]]) |
| **ephemeral 워크트리** | infra 작업 시점에만 `git worktree add` 로 생성 → 작업 후 `git worktree remove` teardown |
| **cycle_hint** | directive 등록 시 nmae 가 지정하는 추천 cycle. `auto` 면 엔진 LLM 분류 |
| **품질게이트 라우팅** | 변경 파일 표면(glob)별로 다른 검사 명령 매핑 (.github→actionlint / tools shell→shellcheck / tools/agent→pytest / gradle·package.json→빌드 sanity) |
| **infra 표면** | `sub-agent.md §2-infra` 허용 경로 = `.github/workflows/**`, `.github/CODEOWNERS`, `tools/**`(비-docs), root 설정, build/lock 파일 (+ bridge repo 는 mmae 신중도 가중) |

## 3) 요구사항
### 기능 요구사항
- [ ] **infra cycle enqueue 허용** — `work_queue.VALID_CYCLES` 에 `infra` 추가. infra directive 가 `ValueError` 없이 큐에 적재된다.
- [ ] **dispatcher infra dispatch 경로** — dispatcher 가 infra 큐 head 항목을 idle 시 launch 한다. standing 4 cycle 과 분리된 ephemeral 경로로 처리한다 (standing 워크트리 가정 없이).
- [ ] **ephemeral 워크트리 lifecycle** — launch 시 `git worktree add <tmp> origin/develop`, 작업 후 (성공/실패/타임아웃 무관) `git worktree remove --force` + `git worktree prune`. teardown 은 lock 해제와 동일하게 `finally` 보장.
- [ ] **infra role prompt** — infra sub-agent 가 `§2-infra` 역할(허용 경로/금지/품질게이트)을 system prompt 로 받는다. `.claude/agents/infra.md` 신설 또는 fallback.
- [ ] **품질게이트 경로별 라우팅** — 변경 파일 표면 → 검사 명령 매핑. 미설치 도구는 graceful skip(`§2-infra` "가용 시" 룰).
- [ ] **cycle_hint=infra 수용** — nmae 가 `register_directive_pending(cycle_hint="infra")` 로 명시 위임 가능. `CycleName` 타입에 `infra` 추가.
- [ ] **rev 연동 유지** — infra PR 생성 시 기존 `enqueue_rev_for_pr_if_any` 가 동일하게 rev 큐에 enqueue. infra PR 도 rev 통과 의무 ([[feedback-rev-e2e-always]]).

### 비기능 요구사항
- **리소스 상한 보존 (ADR-0027 제약 1)**: ephemeral infra 동시 실행 cap = 1 (`MAX_EPHEMERAL_INFRA=1`). 2vCPU/4GB(#1453) thrash 방지 — standing 4 + ephemeral 1 = 최대 5 동시.
- **idle 0 비용 (ADR-0027 제약 2)**: infra 큐가 비면 ephemeral 워크트리 0개. burstiness 와 일치.
- **워크트리 격리 보존 (제약 3)**: ephemeral 워크트리는 directive 별 고유 경로 → standing 워크트리/develop branch 점유와 충돌 없음.
- **wiring blast radius 최소 (제약 4)**: `cycle-status.json` 4 slot / 항시 가동 룰 / forum 채널 구조 불변. 신규는 enqueue 허용 + ephemeral dispatch 경로 + teardown 뿐.
- **고아 정리**: 엔진 crash 로 teardown 누락된 ephemeral 워크트리는 dispatcher 기동 시 + 주기 sweep 으로 `git worktree prune` 회수.

## 4) 범위 / 비범위
### 포함
- 옵션 (a) ephemeral vs (b) 전용 워크트리 비교·결정 (§8 → §9)
- dispatcher / work_queue / subagent_runner 의 infra dispatch 갭 분석 + 변경 지점 명세
- 품질게이트 경로별 라우팅 매트릭스
- ephemeral 워크트리 생성/teardown/고아정리 설계
- rev 영향 + cycle_hint 라우팅 + 동시성 cap

### 제외 (Out of Scope)
- **실제 코드 구현** — 본 spec 은 설계·결정만. 코드는 후속 infra PR (온디맨드 infra 역할 자체가 처리).
- **자율 LLM 분류기가 `cycle_hint=auto` 에서 infra 를 자동 판정하는 룰** — 본 spec 은 명시 `cycle_hint=infra` 경로까지. auto 분류 학습은 후속 (§8 Q2).
- **bridge repo(`bot.py`/discord-daemon) dispatch** — `§2-infra` 상 mmae/helper-launched (별 repo + 운영 hot path). 본 spec 의 ephemeral 경로는 mobruji repo infra 표면만.
- **standing 워크트리 5번째 승격** — ADR-0027 옵션 A 로 이미 거부됨. 본 spec 은 그 결정을 재확인(§9)하되 재평가 trigger 는 ADR-0027 §116-124 가 SoT.
- **ADR-0027 자체 supersede** — 옵션 (b) 채택 시에만 필요하나 본 spec 은 (a) 권고 → ADR 불변.

## 5) 설계

### 5-1) 갭 분석 (코드 지점 — 현재 상태)
| # | 파일:심볼 | 현재 | infra dispatch 시 문제 |
|---|---|---|---|
| G1 | `work_queue.py:23` `VALID_CYCLES` | `{be,fe,rev,plan}` | `enqueue("infra", ...)` → `ValueError("invalid cycle: infra")`. infra directive 적재 불가 |
| G2 | `tools_queue.py:30` `CYCLES` | `(be,fe,rev,plan)` | `dispatch_once` 가 infra 큐를 peek 안 함. 적재돼도 영원히 대기 |
| G3 | `subagent_runner.py:62` `worktree_path` | `WORKTREE_ROOT/mobruji-{cycle}` | infra → `mobruji-infra` standing 워크트리 가정. 존재 안 함 (ADR-0027 가 거부) |
| G4 | `subagent_runner.py:66` `role_prompt` | `<wt>/.claude/agents/{cycle}.md` | `infra.md` 부재 → 최소 fallback role 만 (역할 경로/금지/게이트 미주입) |
| G5 | 품질게이트 | cycle 무관 prompt, 게이트는 워크트리 role 의존 | infra 는 표면별 게이트 라우팅 필요 (be `./gradlew` 부적합) |
| G6 | `tools_cycle.py` `CycleName` | be/fe/rev/plan | `cycle_hint="infra"` 검증 거부 |

### 5-2) 옵션 비교 — (a) ephemeral vs (b) 전용 워크트리

#### 옵션 (a) — ephemeral 워크트리 launch 경로 (**권고**)
- dispatcher 에 infra 전용 경로 추가. infra 큐 head 가 idle 조건 충족 시:
  1. `git worktree add <WORKTREE_ROOT>/mobruji-infra-<directive-slug> origin/develop` (directive 별 고유 경로 — standing 점유 충돌 회피)
  2. infra role system prompt + 품질게이트 라우팅으로 `claude -p` sub-agent 실행
  3. 완료/실패/타임아웃 무관 `finally` 에서 `git worktree remove --force` + `git worktree prune`
- 채택 근거: ADR-0027 옵션 D 와 정합. idle 0 비용 + ephemeral 격리 + standing 룰 불변.
- 비용: dispatcher 에 ephemeral wrapper 분기 + teardown/고아정리 코드. standing 경로보다 lifecycle 복잡.

#### 옵션 (b) — 전용 infra 워크트리 1개 (상시·경량)
- `mobruji-infra` standing 워크트리 1개 추가 + `CYCLES`/`VALID_CYCLES` 에 `infra` 단순 추가. dispatch 경로는 기존 4 cycle 과 동일 (ephemeral lifecycle 불필요).
- 거부 근거:
  - ADR-0027 옵션 A 가 **이미 거부**한 "상시 워크트리" — (b) 는 그 변형이다. "경량"이어도 standing = 항시 가동 룰([[feedback-keep-4-cycles-active]])이 5번째를 idle 점유로 끌어들이고, 2vCPU/4GB(#1453) 헤드룸을 잠식한다 (ADR-0027 제약 1).
  - infra 워크로드는 bursty — standing 워크트리는 대부분 idle (ADR-0027 제약 2).
  - 채택 시 ADR-0027 `superseded_by` 갱신 + 신규 ADR 필요 — 결정 비용 큼.
- 장점(인정): dispatch 경로가 standing 4 cycle 과 동일해 코드 단순. teardown/고아정리 불필요.

#### 비교 매트릭스
| 축 | (a) ephemeral | (b) 전용 standing |
|---|---|---|
| 상시 리소스 비용 | idle 0 (작업 시만) | 항시 점유 (경량이라도 세션 1) |
| ADR-0027 정합 | O (옵션 D 그대로) | X (옵션 A 거부 재현 — supersede 필요) |
| dispatch 코드 복잡도 | 높음 (lifecycle+teardown+고아정리) | 낮음 (CYCLES 1줄 추가) |
| 워크트리 격리 | O (directive 별 고유 경로) | O (전용 standing) |
| bursty 적합 | O (큐 고갈 시 0) | X (idle 점유) |
| 항시 가동 룰 충돌 | 없음 (standing 아님) | 있음 (5번째를 idle 가동 대상화) |
| **결정** | **채택** | 거부 |

### 5-3) ephemeral 워크트리 lifecycle (옵션 a 설계)
```
dispatcher tick
  │
  ├─ infra 큐 peek_next → 항목 있음?
  │     └─ 없음 → skip (ephemeral 0)
  │
  ├─ 동시성 cap 확인 (in_flight ephemeral infra < MAX_EPHEMERAL_INFRA=1)?
  │     └─ 초과 → 대기 (다음 tick)
  │
  ├─ git worktree add  <ROOT>/mobruji-infra-<slug>  origin/develop
  │     └─ 실패 → launch_attempts++ / N회 후 격리 (기존 #1390 패턴 재사용)
  │
  ├─ role = infra.md body (또는 §2-infra fallback)
  ├─ prompt = build_task_prompt("infra", ...) + 품질게이트 라우팅 지시
  ├─ claude -p (start_new_session=True — #1481 프로세스 그룹 kill 보존)
  │
  └─ finally:
        ├─ mark_subagent_completed("infra")  (lock 해제)
        ├─ git worktree remove --force <tmp>
        └─ git worktree prune
```
- **teardown 보장**: 기존 `run_subagent_execution` 의 "어떤 경우에도 lock 해제" `finally` 패턴(`subagent_runner.py:142-160`)을 그대로 확장 — worktree remove 도 같은 finally.
- **고아 정리**: 엔진 SIGKILL 등으로 finally 누락 시 디스크 잔존. dispatcher 기동 + 주기 sweep 에서 `mobruji-infra-*` 패턴 워크트리를 `git worktree list` 로 열거 → in_flight 아니면 `prune`. ([[feedback-stash-drop-unmerged-file]] 정신 — 잔재 정리).

### 5-4) 품질게이트 경로별 라우팅
infra sub-agent 가 push 전 실행. **변경된 파일 표면**(`git diff --name-only origin/develop`)을 glob 매칭 → 매칭된 게이트만 실행. 도구 미설치 시 graceful skip(경고 로그 + pass) — `§2-infra` "가용 시" 룰.

| 변경 표면 (glob) | 게이트 | 미설치 시 |
|---|---|---|
| `.github/workflows/**` | `actionlint` | skip + 경고 |
| `tools/**/*.sh`, `scripts/**/*.sh` | `shellcheck` | skip + 경고 |
| `tools/agent/**/*.py`, `tools/**/*.py` | `pytest`(해당 디렉토리) | skip + 경고 |
| `backend/build.gradle*`, `backend/gradle/**`, `backend/settings.gradle*` | `cd backend && ./gradlew help`(의존성 resolve sanity) | skip |
| `web/package.json`, `web/next.config.*`, lockfile | `cd web && npm run lint`(또는 build sanity) | skip |
| `**/*.yml`, `**/*.yaml`(root 설정) | `yamllint`(가용 시) | skip + 경고 |
| `Dockerfile`, `docker-compose*.yml` | `hadolint`(가용 시) | skip + 경고 |

- 라우팅 함수(가칭 `infra_quality_gate(changed_paths) -> list[GateResult]`): 각 glob 룰을 순회, 매칭 표면이 있으면 명령 실행. 하나라도 fail → push 보류 + 보고. lock 의존 shell test 는 `tools/rev-queue/flock-fallback.sh exec` wrapper 경유 (`§2-rev`/`§2-infra` 동일 가드).
- 본 라우팅은 sub-agent prompt 내 지시 + (가능 시) helper 스크립트 양쪽. 엔진이 게이트를 강제하지는 않고 sub-agent 가 실행·보고 (be/fe 와 동일 자기 책임 모델).

### 5-5) cycle_hint 라우팅 + rev 영향
- **명시 위임**: nmae `register_directive_pending(directive_id, summary, cycle_hint="infra")` → `tools_cycle.CycleName` 에 `infra` 허용 → infra 큐 enqueue. `agent.py:440` "cycle_hint 가 auto 아니면 강제 사용" 룰이 그대로 적용 → infra 로 강제 dispatch.
- **auto 분류**: `cycle_hint=auto` 에서 LLM 이 infra 를 판정하는 것은 OOS(§8 Q2) — 본 spec 은 명시 경로까지.
- **rev 영향**: infra PR 생성 시 `subagent_runner._on_exec_success` → `tq.enqueue_rev_for_pr_if_any("infra", worktree)` 가 기존과 동일하게 동작 (cycle 문자열만 infra). rev 는 standing `mobruji-rev` 워크트리에서 그대로 audit — **rev 워크트리/부하 변경 없음**. infra PR 은 e2e 불가능(workflow/shell)이 흔해 rev 3단계 중 단계 1(no-op pass) 경로 (`rev-e2e-2-stages.md` + [[feedback-rev-e2e-always]]). owner 명시로 보호 영역 변경 PR 의 rev 신중도 가중이 더 일관됨 (ADR-0027 §73).

### 5-6) DB 마이그레이션
- 없음 (엔진/도구 변경, 도메인 엔티티 무관).

## 6) 작업 분할 (예상 PR 리스트 — 후속, 본 spec 은 설계만)
- [ ] PR 1: `work_queue.VALID_CYCLES` + `tools_cycle.CycleName` 에 `infra` 추가 (enqueue 허용 + cycle_hint 수용) — G1/G6
- [ ] PR 2: dispatcher ephemeral infra 경로 + `subagent_runner` ephemeral 워크트리 add/teardown + 동시성 cap — G2/G3
- [ ] PR 3: `.claude/agents/infra.md` (role) + `role_prompt` infra 분기 — G4
- [ ] PR 4: 품질게이트 라우팅 함수 + sub-agent prompt 지시 — G5
- [ ] PR 5: 고아 워크트리 sweep (dispatcher 기동 + 주기)

### 보호 영역 변경 여부 (필수 명시)
- 보호 영역 변경 여부: ☑ 있음 — 단, **본 spec PR 자체는 docs 만(보호 영역 무변경)**. 아래는 후속 구현 PR 의 예상 보호 영역 변경:
  - `.github/workflows/**` — infra 게이트(actionlint 등) CI 연동 시 (PR 4 가능).
  - `tools/agent/**` — dispatcher/work_queue/subagent_runner (보호 영역은 아니나 자율 엔진 hot path → rev 신중도 가중 권고).
  - 후속 PR 들은 각자 `§2-infra` owner(온디맨드 infra 역할)가 처리 + rev 통과.

## 7) 테스트 전략
- **단위**: `work_queue.enqueue("infra", ...)` 가 ValueError 없이 적재 (기존 `test_work_queue.py` 확장). 품질게이트 라우팅 함수 — glob→명령 매핑 + 미설치 graceful skip (mock).
- **dispatch**: `dispatch_once` 가 infra 큐 head 를 ephemeral 경로로 launch (mock `git worktree add`/`claude -p`). teardown 이 finally 에서 호출되는지 (예외 주입 케이스 포함).
- **고아 정리**: in_flight 아닌 `mobruji-infra-*` 워크트리가 sweep 으로 prune 되는지.
- **통합(수동/dry-run)**: `MOBRUJI_SUBAGENT_EXEC` off 에서 부기-only 동작 회귀 무변경 확인 후 단계적 on.
- **mock 전략**: git/claude subprocess 는 mock. 실제 ephemeral 워크트리 생성은 통합 dry-run 1회.

## 8) 오픈 질문
| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | ephemeral 워크트리 경로 root | (a) `<WORKTREE_ROOT>/mobruji-infra-<slug>` (standing 과 동거) / (b) `/home/mobruji/mobruji/.claude/worktrees/agent-<id>` (helper-launched 와 동일 dir, `§2-infra` 명시 경로) | @mobruji-maestro / 구현 PR 2 |
| Q2 | `cycle_hint=auto` 에서 infra 자동 판정 | (a) LLM 분류기에 infra 휴리스틱 추가 / (b) 명시 `cycle_hint=infra` 만 (현 spec) | @nmae / auto-dispatch 후속 spec |
| Q3 | 동시성 cap 값 | (a) `MAX_EPHEMERAL_INFRA=1` (현 권고, 보수적) / (b) 2 (호스트 업그레이드 후) | @mobruji-maestro / #1453 재측 시 |
| Q4 | 품질게이트 강제 위치 | (a) sub-agent 자기 책임(현 be/fe 모델) / (b) 엔진/CI 가 강제 | @nmae / PR 4 |

## 9) 결정 로그
- 2026-06-03: 초안 작성 (status=draft). 자율 엔진의 infra self-dispatch 갭(G1~G6)을 6개 코드 지점으로 박제. 옵션 (a) ephemeral 워크트리 launch 경로를 **권고**, (b) 전용 standing 워크트리를 거부 — 사유: (b) 는 ADR-0027 옵션 A(상시 워크트리)의 변형이라 이미 거부된 결정을 재현하고 supersede 비용이 큼. (a) 는 ADR-0027 옵션 D 와 정합(idle 0 비용 + ephemeral 격리 + standing 룰 불변). 품질게이트 경로별 라우팅 + ephemeral teardown/고아정리 + rev 무변경 + 동시성 cap=1 을 설계. 코드는 후속 5 PR (본 spec 은 설계만).

## 10) 관련 spec / ADR
- `docs/decisions/0027-infra-dedicated-cycle-evaluation.md` — 본 spec 의 상위 결정(옵션 D 채택 / 옵션 A 거부). 본 spec 은 옵션 D 의 **구현 방법** 을 결정.
- `docs/ai-harness/actors/sub-agent.md §2-infra` — infra 역할 정의(허용 경로/금지/품질게이트). 본 spec §5-4 라우팅의 SoT.
- `docs/features/directive-work-queue.md` — dispatcher / work_queue 본체 설계. 본 spec 은 그 `CYCLES`/`VALID_CYCLES` 확장.
- `docs/features/autonomous-cycle-orchestration.md` — 4 워크트리 동시 가동 + worktree lock. 본 spec 의 ephemeral 은 그 standing 룰 불변 전제.
- `docs/features/rev-e2e-2-stages.md` — infra PR 의 rev 단계 1 no-op pass 경로.
- 메모리: [[feedback-worktree-lock]], [[feedback-keep-4-cycles-active]], [[feedback-rev-e2e-always]], [[feedback-stash-drop-unmerged-file]]
