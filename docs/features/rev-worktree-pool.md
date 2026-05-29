---
feature: rev sub-agent 워크트리 pool — parallel rev launch 별 워크트리
slug: rev-worktree-pool
status: draft
owner: @goohong
scope: infra
related_issues: []
related_prs: [1337]
last_reviewed: 2026-05-29
---

# rev sub-agent 워크트리 pool — parallel rev launch 별 워크트리

## 1) 개요 (What / Why)

`docs/features/rev-sla.md §8 Q3` 가 SLA 미달성 시 nmae 의 **별 rev sub-agent 추가 launch (parallel)** 분기를 제안했으나 — `sub-agent.md §1-1` "한 워크트리 = 동시 1 sub-agent" 룰 ([[feedback-worktree-lock]]) 과 충돌. rev 사이클 워크트리는 현재 **`/home/mobruji/mobruji-rev` 단일** 이므로 parallel launch 시 워크트리 lock race 가 발생한다. rev-sla §8 Q3 의 (a)/(b) 두 옵션 (같은 워크트리 재사용 vs launch 차단) 모두 SLA 미달성 escalation 효과를 반감한다:

- **(a) 같은 워크트리 재사용** = git status race + rev queue 멱등성 보장 못 함 (PR 두 건 동시 평가 시 라벨 부착 순서 혼란)
- **(b) launch 차단 + DIGEST 만** = parallel launch 자체 무력화 — SLA escalation 분기의 핵심 (큐 race 해소) 가 사라짐

본 spec 은 **rev 워크트리 pool 구조** 를 제안한다. 즉 `mobruji-rev` 단일 워크트리 대신 **`mobruji-rev-1` / `mobruji-rev-2` / `mobruji-rev-3` ... N 개의 rev 워크트리** 를 사전 생성하여 nmae 가 SLA escalation 시 idle rev 워크트리에 추가 launch 한다. 각 rev 워크트리는 독립된 `git worktree` 로 lock race 없이 동시 진행 가능.

핵심 원칙:
1. **rev pool size N 고정** — 초기 박제값 N=2 (단일 → 2 슬롯). 운영 1개월 후 평균 큐 길이에 따라 조정 (`rev-sla.md §6 PR 7` 회고 spec 후보)
2. **rev pool 이외 워크트리는 단일 유지** — be/fe/plan 은 parallel 가치 낮음 (도메인 작업 race 손해 더 큼). rev 만 큐 race 해소가 명백 가치
3. **워크트리 lock 룰 보존** — 한 rev 워크트리 = 동시 1 rev sub-agent 룰 유지 (pool 1 슬롯 = 1 sub-agent)
4. **cycle-status.json 확장** — 기존 4 actor (`be/fe/rev/plan`) 의 `rev` key 가 단일 dict 였던 것을 array (slot 별) 로 schema 확장. `nmae-cycle-watchdog.md §5-7` 5중 안전망 무손상 보장
5. **강제 메커니즘** — `agent-launch-wrapper.sh` 가 rev launch 시 pool 의 idle slot 자동 선택. nmae 학습 의존 ↓

본 spec 은 `rev-sla.md §8 Q3` closure 후속 — SLA escalation 의 (c) 신설 옵션 ("rev pool idle slot 선택 후 추가 launch") 을 박제하여 (a)/(b) 의 trade-off 를 동시에 해소한다.

## 2) 사용자 시나리오

### 2-1) 정상 rev launch (pool 슬롯 1 사용)

- nmae 가 PR #1234 의 rev sub-agent launch 시점 도래
- `agent-launch-wrapper.sh rev --cycle <N>` 호출 → wrapper 가 `cycle-status.json` 의 `rev` array 에서 idle slot 발견 (slot 1)
- slot 1 워크트리 (`/home/mobruji/mobruji-rev-1`) 에 sub-agent launch — `set-active --slot 1 --pr "#1234"` 박제
- rev sub-agent 가 단계 1 통과 → `set-completed --slot 1 --pr "#1234"`
- pool size 1 / 2 사용 — idle slot 2 (`/home/mobruji/mobruji-rev-2`) 다음 launch 대기

### 2-2) SLA escalation parallel rev launch (pool 슬롯 2 추가)

- PR #1234 rev launch (slot 1 사용 중, T0 + 0m)
- 다른 PR 4건 동시 launch — rev sub-agent 가 큐 head 부터 처리 → PR #1234 차례까지 35분 대기
- T0 + 30m → `watchdog_rev_sla_loop` 미달성 detect → DIGEST push + **`agent-launch-wrapper.sh rev --escalation --pr "#1234"`** 호출
- wrapper 가 `cycle-status.json` 의 `rev` array 에서 idle slot 2 발견 → `/home/mobruji/mobruji-rev-2` 에 별 rev sub-agent launch
- 두 rev sub-agent 가 독립 워크트리 동시 진행 — git status race / 라벨 부착 순서 race 없음
- T0 + 40m → slot 2 가 #1234 단계 1 통과 → 정상 머지 흐름

### 2-3) pool 전부 사용 중 추가 escalation (graceful degradation)

- slot 1 #1234 / slot 2 #1235 사용 중 (둘 다 진행)
- PR #1236 SLA 미달성 → wrapper 가 idle slot 부재 detect → **launch 차단 + DIGEST 추가 push** (`⏰ rev pool 전부 사용 중 — PR #1236 escalation 큐 head 대기 (현재 slot 1/2: #1234/#1235)`)
- pool size N=2 의 의도적 한계 — 무한 parallel launch 차단 (운영 비용 가드)
- slot 1 또는 2 완료 시 nmae 가 다음 큐 head launch

### 2-4) rev pool slot 별 cycle-forum thread 분리

- slot 1 launch 시 wrapper 가 `--cycle 5 --slot 1` → cycle forum thread title `[rev] cycle 5 slot 1 — PR #1234`
- slot 2 launch 시 wrapper 가 `--cycle 5 --slot 2` → 별 thread `[rev] cycle 5 slot 2 — PR #1235`
- 사용자가 forum sidebar 에서 두 rev 작업을 독립 thread 로 추적 가능 — milestone 본문 PATCH 도 slot 별 thread 각각 갱신

## 3) 요구사항

### 기능 요구사항

#### 3-1) rev 워크트리 pool 구조

- [ ] **R-1**: rev 워크트리 N개 사전 생성 (초기 N=2)
  - `/home/mobruji/mobruji-rev-1` ← 현재 `/home/mobruji/mobruji-rev` 의 rename + git worktree 재설정
  - `/home/mobruji/mobruji-rev-2` ← git worktree add 신설 (`develop` branch tracking)
  - 두 워크트리 모두 동일 git remote / branch tracking — `git worktree list` 가시화
- [ ] **R-2**: pool size N 환경변수화 (`REV_POOL_SIZE` default 2) — 운영 1개월 후 평균 큐 길이에 따라 3 / 4 로 조정 가능 (`~/.mobruji/.env` 또는 wrapper env)
- [ ] **R-3**: rev pool 이외 워크트리 (be/fe/plan) 는 단일 유지 — 본 spec 범위 외 (의도적 제외)

#### 3-2) `cycle-status.json` schema 확장

기존 `rev` key 가 단일 dict 였던 것을 **array (slot 별)** 로 확장:

```json
{
  "be": {"in_progress": null, "last_completed": {...}, "note": "...", "idle_since": "..."},
  "fe": {...},
  "plan": {...},
  "rev": [
    {
      "slot": 1,
      "worktree": "/home/mobruji/mobruji-rev-1",
      "in_progress": {"pr": "#1234", "title": "...", "started_at": "..."},
      "last_completed": {...},
      "note": null,
      "idle_since": null
    },
    {
      "slot": 2,
      "worktree": "/home/mobruji/mobruji-rev-2",
      "in_progress": null,
      "last_completed": {...},
      "note": "idle — escalation 대기",
      "idle_since": "2026-05-29T10:00:00Z"
    }
  ],
  "rev_sla": {...}
}
```

- [ ] **R-4**: `tools/cycle-status/update.sh` 의 `rev` sub-command 에 `--slot N` 옵션 추가
  - `update.sh rev set-active --slot 1 --pr "#1234" --title "..."`
  - `update.sh rev set-completed --slot 1 --pr "#1234"`
  - `update.sh rev set-idle --slot 2 --note "..."`
- [ ] **R-5**: `tools/cycle-status/validate.sh` 가 `rev` array 인식 — 모든 slot 의 idle note 의무 (STRICT mode 와 동일) + slot 번호 / 워크트리 경로 정합성 검증
- [ ] **R-6**: `nmae-cycle-watchdog.md §5-7` 5중 안전망 — `cycle_idle_watch_loop` 가 `rev` array 의 모든 slot 을 독립적으로 idle 분류 + inject. 기존 4 actor 분류 로직 무손상 (sibling key 확장 패턴)

#### 3-3) `agent-launch-wrapper.sh` rev pool 슬롯 선택 로직

- [ ] **R-7**: wrapper 가 rev launch 시 `cycle-status.json` 의 `rev` array 에서 idle slot 자동 선택
  - 1순위: `in_progress=null` 인 slot 중 `idle_since` 가 가장 오래된 slot (FIFO)
  - 2순위: 모두 사용 중이면 launch 차단 + DIGEST 추가 push
- [ ] **R-8**: wrapper 가 launch 직전 `update.sh rev set-active --slot N --pr <PR>` 호출 atomic — race 가드 (두 wrapper 동시 호출 시 flock 의무)
- [ ] **R-9**: wrapper 가 sub-agent launch prompt 에 `cd /home/mobruji/mobruji-rev-<slot>` 첫 줄 박음 — sub-agent.md §1-1 워크트리 격리 룰 준수

#### 3-4) sub-agent.md §1-1 / §2-rev 룰 cross-ref 갱신

- [ ] **R-10**: `sub-agent.md §1-1 워크트리 격리 / lock` 의 rev 경로를 `mobruji-rev` 단일에서 `mobruji-rev-<slot>` (slot 1..N) 로 확장 cross-ref
- [ ] **R-11**: `sub-agent.md §2-rev` 의 매 사이클 첫 액션 `rev-queue.sh all` 호출 시점에 slot 번호 inherit (`REV_SLOT` env 또는 launch prompt 명시) — slot 별 독립 큐 처리 가능
- [ ] **R-12**: rev pool 의 모든 slot 가 동일 `rev-queue.sh` 큐를 공유 (큐 자체는 PR 번호 기준 멱등성) — slot 별 큐 분리 X (race 안 발생, 라벨 부착이 멱등 가드)

#### 3-5) `rev-sla.md §8 Q3` closure cross-ref

- [ ] **R-13**: 본 spec status=approved 시점에 `rev-sla.md §8 Q3` row 결정 (c) 신설 — "rev pool idle slot 선택 후 추가 launch" 옵션 박제 + 본 spec SoT cross-ref
- [ ] **R-14**: `rev-sla.md §3-4 Escalation 분기 매트릭스` 의 "nmae 추가 rev launch" 열을 "rev pool slot N 선택 후 launch" 로 cross-ref (행위는 동일하나 워크트리 race 가드 명시)

### 비기능 요구사항

- **신뢰성**: rev pool slot 선택 race 가드 — `flock /var/lock/rev-pool-select.lock` 또는 cycle-status.json atomic write (PR D 본문 결정)
- **관측성**: cron digest signature 추가 — `rev pool 사용률: 1/2 (slot 1=#1234 in progress, slot 2=idle 30m)`
- **회복성**: 워크트리 1개 손상 (예: dirty git status) 시 다른 slot 자동 fallback — wrapper 가 slot health check
- **호환성**: 기존 단일 `mobruji-rev` 워크트리 사용 코드 (sub-agent.md / nmae.md / helper.md) 모두 `mobruji-rev-1` 로 자동 redirect 보존 — symlink 또는 별도 wrapper 단계 (PR A 본문 결정)
- **운영 비용**: pool size N=2 의 의도적 한계 — 무한 parallel launch 차단 (디스크 + CPU + git fetch 부하 가드)

## 4) 범위 / 비범위

### 포함

- §3-1 rev 워크트리 pool 구조 (N=2 초기값)
- §3-2 `cycle-status.json` schema 확장 (`rev` array)
- §3-3 `agent-launch-wrapper.sh` pool slot 선택 로직
- §3-4 `sub-agent.md §1-1 / §2-rev` 룰 cross-ref 갱신
- §3-5 `rev-sla.md §8 Q3` closure cross-ref
- `tools/cycle-status/update.sh` + `validate.sh` 확장
- `nmae-cycle-watchdog.md §5-7` array 인식 cross-ref

### 제외 (Out of Scope)

- be/fe/plan 워크트리 pool — 본 spec 은 rev pool 만 (도메인 작업 parallel 가치 낮음, 별 spec 후보)
- rev 큐 자체의 slot 별 분리 — 큐는 PR 번호 멱등 가드로 동일 큐 공유
- `mobruji-rev-1/2` 외 추가 슬롯 (N=3+) 의 자동 생성 — 초기 N=2 만 고정 (운영 1개월 후 회고 spec trigger)
- pool slot 별 독립 .env 또는 npm cache — 두 워크트리 모두 동일 dependency 공유 (rev 는 파일 수정 금지라 dependency 사용 X)
- rev pool 의 cross-host 확장 (NCP + mac 두 서버) — 본 spec 은 단일 NCP 호스트 pool 만
- rev pool slot 별 reasoning chunk 5분 룰 별도 조정 — `sub-agent.md §1-4` 룰 그대로 (slot 별 독립 5분 카운트)

## 5) 설계

### 5-1) 도메인 모델

- 기존: `RevWorktree` (`/home/mobruji/mobruji-rev` 단일, `06-domain-model.md §4` 등재 후보) / `ReviewedClaudeLabel` / `RevGateCheck`
- 신설 후보 (본 spec accepted 시 등재):
  - **RevWorktreePool** — N 개 rev 워크트리 집합 (`mobruji-rev-1` ... `mobruji-rev-N`)
  - **RevPoolSlot** — pool 의 1 슬롯 (slot 번호 + 워크트리 경로 + in_progress dict)
  - **RevPoolSlotSelector** — wrapper 의 idle slot 선택 알고리즘 (FIFO `idle_since` 기준)
  - **RevPoolFullDigestPush** — pool 전부 사용 중 시 DIGEST escalation entry

### 5-2) API 엔드포인트

해당 없음 (인프라 스크립트 + workflow).

### 5-3) 외부 연동

- `git worktree add` / `git worktree list` / `git worktree remove` — pool slot 생성 / 가시화 / 정리
- `flock /var/lock/rev-pool-select.lock` — race 가드 (또는 cycle-status.json atomic write)
- `tools/cycle-status/update.sh rev set-active --slot N` (확장)
- `agent-launch-wrapper.sh` — pool slot 자동 선택 + sub-agent launch prompt 박제
- `nmae-cycle-watchdog.md §5-7` `cycle_idle_watch_loop` — `rev` array 인식
- `rev-sla.md §3-4` escalation 분기 — pool slot 선택 후 parallel launch

### 5-4) 데이터 흐름 / 시퀀스

```text
[T0] nmae rev sub-agent launch trigger (PR #1234)
    ↓
agent-launch-wrapper.sh rev --cycle 5 --pr "#1234"
    ├─ flock /var/lock/rev-pool-select.lock
    ├─ read cycle-status.json `rev` array
    ├─ select_idle_slot(array)
    │      ├─ slot 1 (idle_since=null, in_progress=null) → 선택
    │      └─ (모두 사용 중이면 → DIGEST push + exit 1)
    ├─ update.sh rev set-active --slot 1 --pr "#1234" --title "..."
    ├─ launch sub-agent (prompt 첫 줄: cd /home/mobruji/mobruji-rev-1)
    └─ flock release
    ↓
sub-agent rev queue 처리 → 단계 1 통과
    ↓
update.sh rev set-completed --slot 1 --pr "#1234"
    ↓
slot 1 idle 복귀 (다음 launch 대기)

[T0 + 30m, SLA 미달성 분기]
watchdog_rev_sla_loop detect → escalation
    ↓
agent-launch-wrapper.sh rev --escalation --pr "#1234"
    ├─ flock
    ├─ select_idle_slot(array)
    │      ├─ slot 1 사용 중 (#1234 진행 중)
    │      └─ slot 2 idle → 선택
    ├─ update.sh rev set-active --slot 2 --pr "#1234" --title "escalation"
    └─ launch sub-agent (prompt 첫 줄: cd /home/mobruji/mobruji-rev-2)
```

### 5-5) DB 마이그레이션

해당 없음 (jsonl / json file 만).

### 5-6) 프론트엔드 화면

해당 없음.

## 6) 작업 분할 (예상 PR 리스트)

- [ ] **PR A** (본 spec 박제, docs only): 본 PR — `docs/features/rev-worktree-pool.md` 신설.
- [ ] **PR B** (`cycle-status.json` schema 확장 + update.sh / validate.sh slot 인식): 본 spec status=approved 후. 보호 영역 X but cycle-status helper 변경 가중도. **호환성 가드**: 기존 단일 dict 형태 `rev` key 인식 → array 로 자동 migrate 1회 (slot=1 wrap).
- [ ] **PR C** (`git worktree add /home/mobruji/mobruji-rev-2` + 기존 `mobruji-rev` → `mobruji-rev-1` rename): 운영자 액션 의무 (PR 본문 운영자 액션 명시) + symlink 가드 (`mobruji-rev` → `mobruji-rev-1` 호환성 1주일 유지 후 제거).
- [ ] **PR D** (`agent-launch-wrapper.sh` pool slot 선택 로직 + flock race 가드): 본 PR B/C 머지 후. wrapper 단일 entry 라 unit test 가능. fake `cycle-status.json` mock 으로 5 시나리오 검증 (slot 1 idle / slot 2 idle / 모두 사용 중 / slot 1 dirty / slot 2 미존재).
- [ ] **PR E** (`sub-agent.md §1-1 / §2-rev` cross-ref + `nmae-cycle-watchdog.md §5-7` array 인식 cross-ref): 본 PR D 머지 후. docs only.
- [ ] **PR F** (`rev-sla.md §8 Q3` closure (c) 신설 옵션 + `§3-4` cross-ref): 본 PR E 머지 후. docs only.
- [ ] **PR G** (운영 1개월 후 회고 spec — pool size N 조정 + slot 사용률 메트릭): SLA 적용 1개월 후 trigger.

### 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: ☐ 없음 (본 PR 한정 — docs only)
- 후속 PR B/D 는 `tools/cycle-status/` + `tools/agent-launch-wrapper.sh` 변경 — 보호 영역 X but daemon / wrapper 변경 가중도
- 후속 PR C 는 운영자 액션 (워크트리 생성 / rename) — `.env` 변경 가능성 (`REV_POOL_SIZE` 추가) — 정보성 보호 영역

## 7) 테스트 전략

### 7-1) pool slot 선택 로직 (PR D unit test)

- fake `cycle-status.json` 5 시나리오:
  - slot 1 idle / slot 2 idle → slot 1 선택 (`idle_since` 더 오래된 쪽)
  - slot 1 사용 중 / slot 2 idle → slot 2 선택
  - 둘 다 사용 중 → DIGEST push + exit 1 확인
  - slot 1 dirty git status → slot 2 fallback 선택 + stderr warning
  - slot 2 미존재 (R-1 R-2 부재) → slot 1 단일 fallback + warning

### 7-2) flock race 가드 (PR D 통합 test)

- 두 wrapper process 동시 호출 → 하나만 slot 1 선택, 다른 하나는 slot 2 또는 차단 확인
- flock 부재 환경 (mac 등) 에서 `flock-fallback.sh` 위임 — 기존 룰 (sub-agent.md §2-rev) 따르기

### 7-3) cycle-status.json migrate 가드 (PR B unit test)

- 기존 단일 dict `rev` key → array `[{...}]` 자동 wrap migrate 1회 검증
- migrate 후 `validate.sh` 통과 + 기존 `set-active` (slot 옵션 부재) 호출 시 slot=1 기본 가정 확인

### 7-4) rev-sla escalation 분기 e2e (PR F 검증)

- watchdog_rev_sla_loop 가 SLA 미달성 detect → wrapper `--escalation` 호출 → slot 2 선택 → 두 sub-agent 독립 동작 확인 (rev-sla.md §3-4 의 "nmae 추가 rev launch" 분기 실제 동작 evidence)

### 7-5) nmae-cycle-watchdog 5중 안전망 보존 (PR E 회귀 가드)

- 기존 4 actor (be/fe/rev/plan) idle 분류 로직이 `rev` array 확장 후에도 무손상 — slot 별 idle 분류 + inject 가 독립 동작 확인

## 8) 오픈 질문

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | 초기 pool size N | (a) N=2 (본 spec 박제값) / (b) N=3 (early generous) / (c) N=1 (현재 + symlink 만, 점진 확장) | @goohong / spec approved 전 |
| Q2 | 기존 `mobruji-rev` → `mobruji-rev-1` rename 시 호환성 symlink 유지 기간 | (a) 1주일 / (b) 영구 (legacy slot=1 호환) / (c) 즉시 제거 (atomic 전환) | @goohong / PR C 구현 시 |
| Q3 | pool slot 선택 race 가드 | (a) `flock /var/lock/rev-pool-select.lock` / (b) `cycle-status.json` atomic write (jq + mv) / (c) python multiprocessing.Lock (bot.py 진입 시점) | @goohong / PR D 구현 시 |
| Q4 | pool slot 별 cycle forum thread 분리 | (a) slot 별 독립 thread (`[rev] cycle N slot M`) / (b) cycle 별 단일 thread (slot 정보는 본문 메타) | @goohong / PR D 구현 시 |
| Q5 | 운영 1개월 후 pool size 조정 trigger | (a) 평균 큐 길이 > 3 → N+1 / (b) escalation 발생률 > 20% → N+1 / (c) 사용자 명시 결정만 | @goohong / SLA 적용 1개월 후 |
| Q6 | be/fe/plan 워크트리 pool 확장 가능성 | (a) 의도적 제외 영구 / (b) 별 spec 후보 (`be-worktree-pool.md` 등) / (c) plan 만 추가 검토 (문서 작업 parallel 가치 있음) | @goohong / SLA 적용 3개월 후 |

## 9) 결정 로그

- **2026-05-29 (plan round 16)**: 초안 작성 (status=draft). `rev-sla.md §8 Q3` closure 후속 — SLA escalation 의 "별 rev sub-agent 추가 launch" 분기가 단일 워크트리 lock 룰 ([[feedback-worktree-lock]]) 과 충돌하는 문제 해소. 옵션 (a) 같은 워크트리 재사용 (git status race) 또는 (b) launch 차단 (escalation 무력화) 모두 trade-off → **(c) rev pool idle slot 선택 후 추가 launch** 신설. 초기 pool size N=2 박제 (점진 확장). 워크트리 lock 룰 보존 (1 slot = 1 sub-agent). cycle-status.json schema 확장 (`rev` key 가 dict → array). 기존 단일 `mobruji-rev` 워크트리 사용 코드 호환성 가드 (symlink 또는 wrapper 단계). 7 PR 분할 (본 spec 박제 → schema → 워크트리 생성 → wrapper 슬롯 선택 → 룰 cross-ref → rev-sla Q3 closure → 회고 spec). 메모리 후보: `[[feedback-rev-pool-parallel-escalation]]` (rev pool 도입 후 escalation 성공률 누적 사례 박제 시 등재). plan round 16 trigger.
