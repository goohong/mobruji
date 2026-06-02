---
feature: rev 거부 / CI 실패 PR 자동 재작업 고리
slug: pr-rework-auto-loop
status: draft
owner: plan
scope: infra
related_issues: [1524, 1518, 1499, 1442]
related_prs: []
last_reviewed: 2026-06-03
---

# rev 거부 / CI 실패 PR 자동 재작업 고리

## 1) 개요 (What / Why)

현 자율 루프는 **구현 → PR → rev 자동 코드 리뷰 → `reviewed:claude` → 자동 머지** 까지만 배선되어 있다 (`tools_queue.enqueue_rev_for_pr_if_any` + `auto-merge-on-rev-pass.yml`). 그러나 rev 가 결함을 발견해 ⛔ 차단하거나 CI(`build-and-test`)가 실패하면 그 PR 은 **방치된다** — 같은 브랜치에서 fix → push → 재검토로 되돌리는 고리가 없다.

이 갭은 밤샘 자율(2026-06-02/03) 실사고로 드러났다:
- #1518 — rev 가 CI 실패(`build-and-test` FAIL)인 #1513 에 `reviewed:claude` 부여. auto-merge 의 `mergeStateStatus=CLEAN` 가드가 머지는 막았으나, **그 PR 을 고칠 주체가 없어** 영구 정체.
- #1499 — 자동 rev task 가 rev-gate 통과 문자열을 안 남겨 scope:web/backend PR 영구 머지 차단. rev 통과 경로조차 deadlock 났는데, fail 경로는 회복 장치가 전무.

본 spec 은 **rev fail / CI fail 신호 → 원 directive 를 fix 모드로 원 구현 사이클(be/fe)에 재dispatch → 같은 브랜치 fix → push → 자동 재검토** 의 닫힌 고리를 설계한다. 무한 재작업·flake 오판·사람 부재 방치를 막는 가드를 핵심으로 둔다.

**대상 액터**: agent 핸들러(`tools_queue` dispatch tick), be/fe 구현 sub-agent, rev sub-agent, 사용자(에스컬레이션 수신), bot.py(dumb conduit).

## 2) 사용자 시나리오

1. **rev 차단 → 자동 재작업**: be 가 PR #N 생성 → rev 가 결함 발견 → `❌ rev changes requested` 코멘트 + `rev:changes-requested` 라벨 부착. agent 핸들러가 이 신호를 감지 → 원 사이클(be)에 fix 모드 directive 적재 → be sub-agent 가 `gh pr checkout #N` 으로 같은 브랜치 진입 → rev 피드백을 task 로 받아 fix → push → CI 재실행 + rev 자동 재검토.
2. **CI 실패 → flake 판별 후 재작업**: PR #N 의 `build-and-test` 가 FAIL. agent 핸들러가 해당 head SHA 에 대해 `gh run rerun --failed` 1회 시도 → (a) 재실행 green = flake 였음, 재작업 안 함 / (b) 재실행도 FAIL = 실결함, fix 모드 재dispatch.
3. **2회 소진 → 사람 에스컬레이션**: 같은 PR 이 fix 후에도 2회까지 재작업했는데 여전히 rev fail/CI fail → 자동 재dispatch 중단. `rework:exhausted` + `needs-human-review` 라벨 + `🔴` 사용자 채널 push("PR #N 자동 재작업 2회 소진 — 사람 개입 필요"). PR 은 hold 상태로 보존.

## 3) 요구사항

### 기능 요구사항
- [ ] **rev fail 신호 표준화** — rev sub-agent 가 결함 차단 시 PR 코멘트에 매직 문자열 `❌ rev changes requested` + 라벨 `rev:changes-requested` 부착. (현 통과 문자열 `✅ rev e2e PR pass` / `📝 rev no-op pass` 의 대칭). rev task 프롬프트(`enqueue_rev_for_pr_if_any` 본문)에 이 fail 규약 추가.
- [ ] **CI fail 감지** — agent 핸들러가 open PR(base develop)의 required check rollup(`gh pr checks <N>`)에서 `build-and-test` 등 FAIL 을 감지. 멱등 가시화 라벨 `ci:failed` 부착.
- [ ] **flake vs 실결함 구분** — CI fail 신호는 곧바로 재작업으로 보내지 않고, 해당 head SHA 에 대해 `gh run rerun --failed` 를 **SHA 당 1회** 시도. 재실행 통과 = flake → 재작업 취소(라벨 회수). 재실행도 실패 = 실결함 → 재작업. rev fail 은 판단 신호라 항상 실결함으로 취급(rerun 불요).
- [ ] **fix 모드 재dispatch** — rework-eligible PR 의 **원 사이클**(be/fe)에 fix 모드 directive 적재. task 본문에 (a) `gh pr checkout #N`(같은 브랜치, 신규 PR 금지) (b) rev 피드백 코멘트 발췌 + CI 실패 로그(`gh run view --log-failed`, 절단) (c) fix → push → CI/rev 자동 재검토 안내를 첨부.
- [ ] **원 사이클 귀속** — PR 본문 `directive: <id>` xref(있으면) → 원 directive 의 `assigned_cycle`. 없으면 `scope:*` 라벨로 추론(scope:web → fe, 그 외 → be).
- [ ] **재작업 상한 N=2 + 에스컬레이션** — PR 당 재작업 2회 소진 후에도 fail → 자동 재dispatch 중단. `rework:exhausted` + `needs-human-review` 라벨 + `🔴` 사용자 채널 push + nmae 알림. hold 보존.
- [ ] **무한 루프 방지(head SHA dedup)** — (PR, head_sha) 당 재작업 1회만. fix push 로 head SHA 가 바뀌어야 다음 재작업 카운트 진입. 같은 SHA 재신호는 no-op.

### 비기능 요구사항
- **감지·재dispatch 주체 = agent 핸들러**(`tools_queue` dispatch tick), bot 루프/CI 워크플로우 아님. 근거 §5-2.
- **graceful** — 재작업 감지/적재 실패가 다른 사이클 dispatch 를 차단하지 않는다(`enqueue_rev_for_pr_if_any` 와 동일 정책).
- **gh 호출 throttle** — CI fail 스캔은 매 tick 전수 스캔이 아니라 throttle(예: 5분 간격 또는 N tick 마다)로 제한해 rate-limit·부하 회피.
- **idempotency** — 모든 적재는 directive_id 멱등(`rework-pr-<N>-<attempt>`). 라벨 부착·코멘트는 중복 방지 마커 기반.
- **#1442 회귀 방지** — 무한 재nudge / 사용자 채널 스팸 금지. 재작업 신호는 SHA dedup, 사용자 채널 push 는 **에스컬레이션 1회만**(평상시 재작업은 cycle forum thread 보고).

## 4) 범위 / 비범위

### 포함
- rev fail / CI fail 신호 표준(라벨 + 매직 문자열).
- agent 핸들러의 감지·flake 판별·fix 모드 재dispatch 로직.
- 재작업 상한·SHA dedup·사람 에스컬레이션 가드.
- fix 모드 sub-agent task 규약(같은 브랜치 checkout, 피드백 첨부).

### 제외 (Out of Scope)
- **머지된 PR 의 회귀 자동 revert** — 별도 spec `dev-deploy-auto-revert.md` 가 담당(`regression:dev` → revert PR). 본 spec 은 **머지 전** OPEN PR 의 재작업만.
- **post-merge 감사 loop 재설계(#1442 단계 2)** — 별도. 본 spec 은 그 교훈(무한 nudge / 스팸 회피)을 가드로 차용만.
- **rev sub-agent 자체의 e2e 판정 로직** — `rev-e2e-2-stages.md` 가 SoT. 본 spec 은 fail 신호 규약만 추가.
- **CI 자체 안정화(flake 근절)** — 본 spec 은 flake 를 rerun 1회로 흡수만. 근본 flake 추적은 OOS.
- **auto-merge 게이트 변경** — `auto-merge-on-rev-pass.yml` 그대로. 본 spec 은 머지 전 단계만 손댐.

## 5) 설계

### 5-1) 신호 모델 — 라벨이 SoT

재작업 판단은 PR 라벨 + 코멘트 매직 문자열을 durable·멱등 신호로 쓴다(이벤트 snapshot 신뢰 X, 라이브 라벨 재조회 — `auto-merge-on-rev-pass.yml #1419` 교훈).

| 신호 | 부착 주체 | 의미 |
|---|---|---|
| `rev:changes-requested` + 코멘트 `❌ rev changes requested` | rev sub-agent | rev 가 결함 발견 — 실결함, 즉시 재작업 대상 |
| `ci:failed` | agent 핸들러(감지 시) | required check FAIL 멱등 가시화 마커 |
| `rework:in-progress` | agent 핸들러(재dispatch 시) | fix 모드 directive 적재됨 — 중복 적재 가드 |
| `rework:exhausted` | agent 핸들러(소진 시) | 재작업 2회 소진 — 자동 재dispatch 종료 |
| `needs-human-review` | agent 핸들러(소진 시) | 사람 개입 필요(기존 보호영역 라벨 재사용) |

`rev:hold`(기존 — author rebase / nmae 결정 대기)와 **구분**한다. `rev:hold` = "사람/nmae 판단 대기"(재작업 안 함), `rev:changes-requested` = "자동 재작업 대상". 두 라벨 동시 부착 시 `rev:hold` 우선(재작업 skip).

### 5-2) 감지·재dispatch 주체 결정 — agent 핸들러 (bot 루프/CI 아님)

**결정: agent 핸들러(`tools_queue` dispatch tick)에서 감지하고 work_queue 에 적재한다.** 근거:

1. **큐·directive-board 는 NCP 호스트 로컬** — fix 모드 재작업은 `work_queue` 적재 + `directive-board` 상태 전이 + cycle forum 보고가 필요한데, 이 상태는 NCP 호스트에만 있다. CI 워크플로우(격리 러너)는 이 큐에 적재할 수 없다.
2. **CI 의 역할 = 라벨링까지만** — CI 는 `build-and-test` 결과를 PR 상태로 남길 뿐, 재dispatch 는 못 한다. 따라서 CI fail 감지는 agent 핸들러가 `gh pr checks` 로 읽는다(신규 워크플로우 불요). `dev-deploy-auto-revert.md` 가 git ops 를 CI 에 둔 것과 대비 — 거기는 git revert(격리 필요)고, 여기는 큐 적재(호스트 로컬)다.
3. **기존 rev 자동 트리거의 자연스러운 형제** — `enqueue_rev_for_pr_if_any` 가 이미 같은 위치(`tools_queue.py`)에서 gh 폴링 + 적재를 한다. 재작업은 그 대칭 함수다.
4. **bot.py 는 dumb conduit 유지** — `directive-board-event-driven-redesign.md §2` 의 "bot.py = dumb conduit" 원칙 보존. polling sync_loop 부활 금지(#1442 스팸 교훈).

#### 두 트리거 경로

| 경로 | 트리거 시점 | 위치 | 비용 |
|---|---|---|---|
| **rev fail** | rev sub-agent 완료 직후 | `subagent_runner._on_exec_success`(cycle==rev 분기) 또는 신규 `_on_rev_verdict` | event-driven, 저비용 |
| **CI fail** | 비동기(완료 이벤트 없음) | `tools_queue` 신규 throttled 스캔 `scan_prs_for_rework()` (dispatch tick 내, N분 간격) | 폴링, throttle 필수 |

rev fail 은 완료 이벤트가 있으니 event-driven. CI fail 은 rev/구현 완료와 무관하게 비동기 발생하므로 throttled 스캔. 두 경로 모두 `enqueue_rework_for_pr()` 공통 함수로 수렴.

### 5-3) 핵심 함수 (구현은 후속 PR — 시그니처만)

```text
tools_queue.enqueue_rework_for_pr(pr_num, reason, *, worktree) -> str | None
  # reason ∈ {"rev_fail", "ci_fail"}
  # 1. 가드 체크: rev:hold 있으면 skip / rework:in-progress 있으면 skip
  #    / rework:exhausted 있으면 skip.
  # 2. head_sha 조회. rework state(rework:pr-<N>)의 last_rework_sha == head_sha
  #    이면 skip(SHA dedup — 같은 SHA 중복 재작업 방지).
  # 3. reason=="ci_fail" → flake 판별: 이 SHA 에 rerun_attempted 아니면
  #    `gh run rerun --failed` 1회 + rerun_attempted 마킹 후 return(다음 스캔에서
  #    결과 재평가). rerun 후에도 fail 이면 진짜 재작업.
  # 4. attempt = rework state.attempts. attempt >= 2 → escalate() 후 return.
  # 5. 원 사이클 귀속(PR 본문 directive xref → assigned_cycle, 없으면 scope 라벨).
  # 6. enqueue_directive(cycle, f"rework-pr-{pr_num}-{attempt+1}", title, fix_task)
  #    + rework:in-progress 라벨 + attempts++ + last_rework_sha=head_sha 기록.

tools_queue.scan_prs_for_rework(*, now) -> list   # throttled, dispatch_once 내 호출
  # gh pr list --state open --base develop --json number,labels,headRefOid
  # 각 PR: rev:changes-requested 또는 build-and-test FAIL → enqueue_rework_for_pr.

tools_queue._escalate_rework(pr_num)   # rework:exhausted + needs-human-review
  # + 🔴 사용자 채널 push(1회) + nmae 알림. 재dispatch 중단.
```

fix 모드 task 본문(예):
```text
PR #<N> (branch <branch>) 재작업 (fix 모드, <reason>).
1. `gh pr checkout <N>` 으로 같은 브랜치 진입 — 신규 PR 만들지 말 것.
2. 아래 피드백/실패 로그를 반영해 fix:
   --- rev 피드백 ---
   <rev 코멘트 발췌>
   --- CI 실패 로그 ---
   <gh run view --log-failed 절단>
3. 품질 게이트 통과 후 같은 브랜치에 push (push 가 CI 재실행 + rev 자동 재검토 트리거).
구현만 — 신규 PR/이슈 만들지 말 것.
```

### 5-4) 데이터 흐름 / 시퀀스

```
구현 sub-agent → PR #N ──→ rev 자동 검토(enqueue_rev_for_pr_if_any)
                                  │
                   ┌──────────────┴───────────────┐
              ✅ pass                          ❌ fail
        reviewed:claude                rev:changes-requested
                │                              │
          auto-merge                  [agent 핸들러 감지]
                                               │
        CI fail(비동기) ── ci:failed ──→ scan_prs_for_rework (throttled)
                                               │
                                    flake 판별(ci_fail: rerun 1회)
                                               │ 실결함
                                    enqueue_rework_for_pr
                                               │
                              attempt<2 ┌──────┴──────┐ attempt>=2
                                        │             │
                          fix 모드 재dispatch    _escalate_rework
                          (원 사이클, 같은 브랜치)  rework:exhausted
                                        │         + needs-human-review
                            gh pr checkout → fix   + 🔴 사용자 push(1회)
                            → push → CI/rev 재검토
                                        │
                                 (고리 반복, SHA dedup)
```

### 5-5) DB 마이그레이션
없음. 재작업 상태는 directive state(`rework:pr-<N>` = `{attempts, last_rework_sha, rerun_attempted}`)에 보관 — 기존 `ev.get_state/set_state` 활용. DB·엔티티 변경 없음.

### 5-6) 프론트엔드 화면
없음(개발 사이클 내부 인프라).

## 6) 작업 분할 (예상 PR 리스트)

- [ ] **PR 1 (본 spec, plan)**: docs/features 신설 + 라벨 5종 사전 등록(`scripts/setup-labels.sh`: `rev:changes-requested` `ci:failed` `rework:in-progress` `rework:exhausted`). `needs-human-review` 는 기존.
- [ ] **PR 2 (be)**: `tools_queue.enqueue_rework_for_pr` + `scan_prs_for_rework`(throttle) + `_escalate_rework` + dispatch_once 내 스캔 호출 + pytest(가드/dedup/escalation/flake-rerun).
- [ ] **PR 3 (be)**: rev fail 신호 표준화 — `enqueue_rev_for_pr_if_any` rev task 프롬프트에 fail 규약(`❌ rev changes requested` + `rev:changes-requested`) 추가 + `_on_exec_success` rev 완료 분기에서 fail 감지 시 즉시 `enqueue_rework_for_pr`. `.claude/agents/rev.md` 에 fail 출력 규약 명시.
- [ ] **PR 4 (be, 선택)**: cycle forum 재작업 보고 정형(시작/완료/소진 양식) + #1442 스팸 가드 회귀 테스트.

### 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: ☑ 있음 — 변경 파일과 사유:
  - `scripts/setup-labels.sh` — 신규 라벨 4종 멱등 등록(보호영역 아님이나 라벨 체계 변경 가시화). **워크플로우(`.github/workflows/**`) 신규/변경 없음** — CI fail 감지는 agent 핸들러의 `gh pr checks` 폴링이라 신규 워크플로우 불요. 이 점이 본 설계의 핵심(보호영역 최소 변경).

## 7) 테스트 전략

- **단위(pytest, PR 2/3)**:
  - SHA dedup: 같은 head_sha 재신호 → 두 번째 적재 안 함.
  - 상한: attempts=2 도달 시 `_escalate_rework` 호출 + `rework:exhausted` 부착 + 재dispatch 중단.
  - flake: `ci_fail` 첫 신호 → rerun 마킹 + 적재 안 함 / rerun 후에도 fail → 적재.
  - 가드: `rev:hold` 있으면 skip / `rework:in-progress` 중복 적재 skip.
  - 귀속: directive xref 있으면 그 cycle, 없으면 scope:web→fe / 그 외→be.
  - 스팸 가드(#1442): 사용자 채널 push 는 에스컬레이션 1회만(평상 재작업은 forum thread).
- **gh/subprocess mock**: `gh pr list/checks/run rerun/view` 는 fake-process 스텁(`test_tools_queue.py` 패턴 재사용).
- **수동 sanity(배포 후)**: 의도적으로 깨지는 PR 1건 → rev fail → 자동 재작업 1회 → fix → 재검토 green 1회 확인([[feedback-verify-and-iterate]]). 같은 PR 2회 fail → 에스컬레이션 push 1회 확인.

## 8) 오픈 질문

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | 재작업 상한 N | (a) 2회(기본, 권장) / (b) 3회 | @goohong / 구현 전 |
| Q2 | CI fail 스캔 throttle 간격 | (a) 5분 cron-tick / (b) auto-merge 처럼 check_suite 이벤트 연동(워크플로우 필요 — 보호영역) | @goohong |
| Q3 | flake 판별 rerun 횟수 | (a) SHA 당 1회(권장) / (b) 2회 | @goohong |
| Q4 | rev fail 시 원 사이클이 busy 면 | (a) 큐 적재 후 대기(기본, work_queue 가 idle 시 launch) / (b) 우선순위 urgent 부여 | @goohong |

## 9) 결정 로그

- 2026-06-03: 초안 작성(status=draft). 감지·재dispatch 주체 = agent 핸들러(`tools_queue` tick) 확정 — 큐/directive-board 호스트 로컬성 근거(§5-2). flake 는 SHA 당 rerun 1회로 흡수, rev fail 은 항상 실결함. 상한 2회 후 `🔴` 에스컬레이션 1회 push(#1442 스팸 회피). head SHA dedup 으로 무한 루프 차단. 신규 워크플로우 0(보호영역 최소 변경) — CI fail 은 `gh pr checks` 폴링.

## 10) 관련

- 빌드 위에 얹힘: `cycle-backlog-and-auto-merge-hooks.md`(auto-merge), `directive-work-queue.md`(work_queue/dispatch), `directive-board-event-driven-redesign.md`(event-driven, dumb conduit)
- 신호 규약 SoT: `rev-e2e-2-stages.md`(rev 단계), `rev-gate-required-check-enforcement.md`(rev-gate 매직 문자열)
- 대칭 spec(머지 후): `dev-deploy-auto-revert.md`(머지된 회귀 → revert PR)
- 교훈 이슈: #1518(rev CI green 미확인 승인) / #1499(rev-gate deadlock) / #1442(무한 재nudge·스팸)
- 코드: `tools/agent/tools_queue.py`(`enqueue_rev_for_pr_if_any` 형제), `tools/agent/subagent_runner.py`(`_on_exec_success`), `.github/workflows/auto-merge-on-rev-pass.yml`
- 메모리: [[feedback-rev-e2e-always]] [[feedback-verify-and-iterate]] [[feedback-evidence-based-root-cause]] [[feedback-autonomous-default]]
