---
feature: rev sub-agent 단계 1 응답 SLA 및 미달성 시 nmae 강제 escalation
slug: rev-sla
status: draft
owner: @goohong
scope: infra
related_issues: []
related_prs: [1329, 1332, 1333, 1334, 1340, 1341]
last_reviewed: 2026-05-29
---

# rev sub-agent 단계 1 응답 SLA 및 미달성 시 nmae 강제 escalation

## 1) 개요 (What / Why)

`rev-e2e-3-stages.md §3-1` 가 모든 type:* PR 에 rev 단계 1 통과 (`reviewed:claude` 라벨 + ✅/📝/❌ 코멘트) 의무를 박제했으나 **응답 시간 SLA** 가 부재하다. 현 상태:

- rev sub-agent launch 후 단계 1 코멘트가 nmae watchdog idle 분기 (10분 무활동) 전까지는 idle 분류 X
- launch → reasoning chunk 5분 (`sub-agent.md §1-4`) → 도중 PR rev 큐 race / 다른 PR 끼어들기 → 한 PR 의 단계 1 코멘트 지연 10~30분 사례 존재
- helper / 사용자 입장에서 \"내 PR 머지가 왜 안 되는가\" 가시화 부재 — DIGEST 도 단계 1 통과 시점에만 push
- security 분류 PR (`emergency-hotfix-flow.md §3-1` 기준 + `security-disclosure-flow.md §3-2` 🔴 critical-public) 은 더 강한 SLA 필요 — 사용자 가시 노출 중 매분 risk

본 spec 은 rev 단계 1 의 **응답 SLA 목표값** + **미달성 detection** + **nmae 강제 escalation 분기** 를 박제한다. **차단이 아니라 가시화 + nmae 우선순위 조정 trigger** — sub-agent 가 단계 1 통과 timing 을 자율 최적화하도록 SLA 메트릭만 박제, 강제 머지 / 강제 회수는 안 함.

핵심 원칙:
1. **SLA = 목표값** — 미달성 ≠ fail. 미달성 시 nmae 가 우선순위 조정 또는 추가 rev launch
2. **분류별 SLA 차등** — 정규 / hotfix / security 3 등급
3. **메트릭 박제** — `cycle-status.json` 또는 별 `rev-sla-metrics.jsonl` 에 응답 시간 evidence 보존 → 후속 회고 spec trigger
4. **강제 메커니즘** — nmae `watchdog_rev_sla_loop` 신설 (`nmae-cycle-watchdog.md` 5중 안전망 확장) — sub-agent / 사용자 학습 의존 ↓

본 spec 은 prose 룰이 아니라 **bot.py watchdog loop + cycle-status helper 갱신 + DIGEST push 분기** 로 강제 ([[feedback-evidence-based-root-cause]] §17).

## 2) 사용자 시나리오

### 2-1) 정규 PR (정상 SLA 안 처리)

- helper / be / fe sub-agent 가 PR 생성 (T0)
- nmae 가 rev sub-agent launch (T0 + 1m)
- rev sub-agent 가 `rev-queue.sh all` → 단계 1 PR 발견 → 7-step 평가 → `reviewed:claude` 라벨 + 코멘트 (T0 + 5~15m)
- SLA 목표값 30분 안 → 통과 → DIGEST 미push (정상)
- nmae 가 auto-merge 진행 → PR 머지

### 2-2) 정규 PR (SLA 미달성 → escalation)

- helper / sub-agent PR 생성 (T0)
- nmae 가 rev launch 큐 등록 (T0 + 1m)
- 다른 PR 4건 동시 launch → rev sub-agent 가 큐 head 부터 처리 → 본 PR 차례까지 35분 대기
- T0 + 30m → `watchdog_rev_sla_loop` 미달성 detect → DIGEST push (`⏰ PR #1234 rev 단계 1 SLA 미달성 (35m elapsed)`) + nmae 가 별 rev sub-agent 추가 launch (parallel) — 큐 race 해소
- T0 + 40m → 단계 1 통과 → 정상 머지 흐름

### 2-3) security 분류 PR (강화 SLA + 즉시 escalation)

- security 사고 PR 생성 (T0) — `type:emergency-hotfix` 라벨 + body `security` 키워드
- `rev-gate.yml` whitelist 매칭 → 즉시 머지 가능 (rev 단계 1 게이트 면제)
- 머지 후 `EmergencyHotfixFollowupIssue` 자동 신설 → 사후 rev 단계 2 launch
- 본 spec SLA 적용 = **사후 rev 단계 2** 응답 시간 30분 (정규 단계 1 과 동일 등급으로 박제) — 별도 박제
- T0 + 30m → 단계 2 미통과 → `watchdog_rev_sla_loop` security 분기 → DIGEST + Discord 본 채널 + 사용자 reply 3 채널 동시 push (정규 PR 은 DIGEST 1 채널만)

### 2-4) helper / fe sub-agent 가 본인 PR rev 진행 상황 확인

- helper / fe sub-agent 가 자기 PR push 후 `discord-reply.sh --rev-status <PR>` 호출
- 응답: `rev단계1 큐 위치: 3 / 등록 시각: T0 + 1m / SLA 목표 T0+30m / 현재 elapsed 12m / 단계 1 코멘트 부착: 미달`
- sub-agent 가 자기 다음 사이클 결정 가능 — 대기 또는 다른 백로그 선행

## 3) 요구사항

### 기능 요구사항

#### 3-1) SLA 목표값 매트릭스

| PR 분류 | 단계 1 SLA | 단계 2 SLA | 단계 3 SLA | 미달성 시 분기 |
|---|---|---|---|---|
| 정규 type:* | 30분 | 24시간 | 7일 | DIGEST push + nmae 별 rev launch |
| `type:release` | 면제 (사용자 명시 확인) | 24시간 | 7일 | 단계 1 면제, 단계 2/3 동일 |
| `type:emergency-hotfix` (정규) | 면제 (whitelist 머지) | 30분 | 24시간 | 단계 1 면제, 단계 2 강화 (정규 단계 1 등급) |
| `type:emergency-hotfix` + body `security` (🔴 critical-public) | 면제 | 15분 | 24시간 | DIGEST + Discord 본 채널 + 사용자 reply 3 채널 |
| `type:docs` (no-op pass) | 5분 (📝 no-op 즉시) | 면제 (코드 변경 X) | 면제 | DIGEST push |

본 매트릭스 출처: 정규 = 본 spec 박제 / hotfix = `emergency-hotfix-flow.md §3-1` / security = `security-disclosure-flow.md §3-2` 🔴 critical-public 분류.

#### 3-2) 측정 시작 / 종료 시점 정의

- **측정 시작 (T0)**:
  - 단계 1: nmae 가 rev sub-agent launch 큐 등록 시점 (`rev-queue.sh register <PR>` 호출 또는 wrapper launch 시점)
  - 단계 2: PR 머지 완료 시점 (mergedAt, GitHub API)
  - 단계 3: release PR 머지 시점 (`type:release` 라벨 PR mergedAt)
- **측정 종료**:
  - 단계 1: `reviewed:claude` 라벨 부착 시각 + 통과 코멘트 (✅/📝/❌) 부착 시각 중 늦은 쪽 (둘 다 만족해야 통과)
  - 단계 2: `rev-post-merge-pass` 또는 `regression:dev` 라벨 부착 시각
  - 단계 3: `rev-prod-pass` 또는 `regression:prod` 라벨 부착 시각

#### 3-3) `watchdog_rev_sla_loop` 신설 (`nmae-cycle-watchdog.md` 확장)

- [ ] bot.py 에 신설 loop `watchdog_rev_sla_loop` — 1분 간격 polling
- [ ] 매 polling 마다:
  1. `gh pr list --base develop --state open --label "type:*" --json number,labels,createdAt,comments` 결과 read
  2. 각 PR 의 분류 → §3-1 매트릭스 SLA 적용
  3. T0 부터 elapsed 계산 → SLA 초과 PR 추출
  4. 이미 escalation push 된 PR 은 skip (멱등성 — `rev-sla-metrics.jsonl` 에 `escalated_at` 박제 시 1회만)
  5. 미달성 PR 발견 → §3-4 escalation 분기
- [ ] 학습 의존 ↓ — sub-agent 가 자기 PR 의 SLA 시각을 계산하지 않아도 자동 escalation

#### 3-4) Escalation 분기

| 미달성 분류 | DIGEST | Discord 본 채널 | 사용자 reply | nmae 추가 rev launch |
|---|---|---|---|---|
| 정규 단계 1 | 🟢 (의무) | X | X | 🟢 (parallel) |
| 정규 단계 2 | 🟢 | X | X | 🟢 |
| 정규 단계 3 | 🟢 | X | X | 🟢 |
| `type:docs` 단계 1 | 🟢 | X | X | 🟢 |
| security (🔴) 단계 2 | 🟢 | 🟢 | 🟢 (reply) | 🟢 (최우선 큐 head) |

#### 3-5) `rev-sla-metrics.jsonl` 박제

- [ ] 모든 rev 응답 evidence (시작 / 종료 / SLA 결과) 를 `~/.mobruji/rev-sla-metrics.jsonl` append
- [ ] entry schema:
  ```json
  {
    "pr_number": 1234,
    "stage": "1",
    "pr_category": "regular|release|hotfix|security|docs",
    "t0_iso": "2026-05-29T10:00:00Z",
    "completed_iso": "2026-05-29T10:25:00Z",
    "elapsed_seconds": 1500,
    "sla_target_seconds": 1800,
    "sla_met": true,
    "escalated": false,
    "escalated_at_iso": null,
    "escalation_channels": []
  }
  ```
- [ ] 후속 회고 spec — 월간 SLA 달성률 메트릭 도출 (별 PR 후보)

#### 3-6) sub-agent SLA self-query 도구

- [ ] `tools/rev-queue/rev-sla.sh <PR_number>` 신설 (별 PR 후보)
- [ ] 출력: 큐 위치 / T0 / SLA 목표 / 현재 elapsed / 단계 1 통과 여부
- [ ] helper / fe / be sub-agent 가 자기 PR 진행 상황 확인 가능 — 학습 의존 ↓

### 비기능 요구사항

- **관측성**: 모든 SLA 측정 evidence 가 `rev-sla-metrics.jsonl` 1 파일에 박제 — 후속 회고 시 evidence 100% 보존
- **신뢰성**: `watchdog_rev_sla_loop` 1분 polling — escalation 지연 최대 1분
- **회복성**: jsonl 손상 시 graceful skip (다음 polling 에서 재계산) — daemon 죽지 않음
- **남용 방지**: escalation 이 큐 race 만 해소 — 강제 머지 / 강제 통과 X (sub-agent 자율 평가 유지)
- **호환성**: 기존 `rev-e2e-3-stages.md` / `rev-qa-protocol.md` / `nmae-cycle-watchdog.md` 본문 수정 없이 enhancement 만 — `watchdog_rev_sla_loop` 는 5중 안전망 추가

## 4) 범위 / 비범위

### 포함

- §3-1 SLA 목표값 매트릭스 (5 분류)
- §3-2 측정 시작 / 종료 시점 정의
- §3-3 `watchdog_rev_sla_loop` 신설 spec (본문은 별 PR — 본 spec 은 요구사항만 박제)
- §3-4 Escalation 분기 매트릭스
- §3-5 `rev-sla-metrics.jsonl` 박제 schema
- §3-6 sub-agent self-query 도구 spec
- `nmae-cycle-watchdog.md §5-7` 4중 안전망 cross-ref 보강 (5중으로 확장)
- `rev-e2e-3-stages.md §3` cross-ref 보강 — SLA 박제

### 제외 (Out of Scope)

- `watchdog_rev_sla_loop` 본문 (별 PR — bot.py 수정, 보호 영역 X but daemon 변경 — rev 사이클 가중도 추가)
- `rev-sla.sh` script 본문 (별 PR — `tools/rev-queue/` 신설)
- `rev-sla-metrics.jsonl` 월간 회고 spec (별 spec 후보)
- 강제 머지 / 강제 회수 분기 (의도적 제외 — sub-agent 자율 평가 유지)
- SLA 미달성 PR 의 자동 close / 자동 revert (의도적 제외)
- rev 단계 1 코멘트 본문 형식 변경 (`rev-qa-protocol.md §5-9` SoT 유지)

## 5) 설계

### 5-1) 도메인 모델

- 기존: `ReviewedClaudeLabel`, `RevGateCheck`, `RevGateAuditCheck`, `EmergencyHotfixLabel`, `EmergencyHotfixSeverity` (`06-domain-model.md §4` 등재)
- 신설 후보 (본 spec accepted 시 등재):
  - `RevSlaTarget` — PR 분류별 SLA 목표값 (정규 단계 1 = 30분 등)
  - `RevSlaMetricEntry` — `rev-sla-metrics.jsonl` 1 줄 entry schema
  - `RevSlaEscalation` — 미달성 시 escalation 채널 매트릭스
  - `WatchdogRevSlaLoop` — bot.py loop 명칭

### 5-2) 외부 연동

- `gh pr list --json ...` (PR 분류 / T0 추출)
- `gh pr view <PR> --json labels,comments` (단계 1 통과 detect)
- `discord-reply.sh --digest` (DIGEST escalation)
- `discord-reply.sh --reply` (security 분류 사용자 reply)
- `tools/cycle-status/update.sh` (escalation 시 cycle-status.json 갱신 — 선택)
- `nmae-cycle-watchdog.md` 4중 안전망 (5중으로 확장)
- `rev-e2e-3-stages.md` 단계별 통과 시점 정의

### 5-3) 데이터 흐름 / 시퀀스

```text
[T0] nmae rev sub-agent launch 큐 등록 (rev-queue.sh register <PR>)
    └ rev-sla-metrics.jsonl entry append (sla_met=null, escalated=null)
[T0 + 1m] watchdog_rev_sla_loop polling
    ├ gh pr list → PR 분류 추출
    ├ §3-1 매트릭스 SLA 적용 → elapsed 계산
    └ SLA 안 → skip
[T0 + 30m] watchdog_rev_sla_loop polling
    ├ 정규 단계 1 SLA 초과 detect
    ├ rev-sla-metrics.jsonl 의 escalated=false → escalation 분기
    ├ §3-4 매트릭스 → DIGEST push
    ├ nmae 별 rev sub-agent 추가 launch (parallel)
    └ jsonl 갱신 (escalated=true, escalated_at_iso, escalation_channels)
[T0 + 40m] rev sub-agent 단계 1 통과 (reviewed:claude 라벨 + ✅ 코멘트)
    └ watchdog_rev_sla_loop polling → sla_met=false (지연), completed_iso 박제
[T0 + 41m] nmae auto-merge 진행
```

### 5-4) DB 마이그레이션

해당 없음 (jsonl file 만).

### 5-5) 프론트엔드 화면

해당 없음.

## 6) 작업 분할 (예상 PR 리스트)

- [ ] PR 1 (본 spec 박제): docs only — 본 PR
- [ ] PR 2 (`nmae-cycle-watchdog.md §5-7` 4중 → 5중 확장 cross-ref 보강): 본 spec status=approved 후 별 사이클
- [ ] PR 3 (`watchdog_rev_sla_loop` 본문 신설 — bot.py + helper script): 본 spec status=approved 후 — daemon 변경 (보호 영역 X but 가중도). **상세 설계 = §6-PR3 detailed design** (사전 박제 — plan round 12 / 2026-05-29).

#### §6-PR3 detailed design (사전 박제, plan round 12)

> 본 sub-section 은 bot.py 변경 PR 3 launch 시 first-class reference. 실제 Python 코드는 PR 3 본 사이클 — 본 spec 은 schema / 알고리즘 / 룰만.

**1) `cycle-status.json` schema 확장** (`~/.mobruji/cycle-status.json`, `tools/cycle-status/update.sh` 갱신)

기존 top-level keys (`be`, `fe`, `rev`, `plan`) 와 별 sibling key `rev_sla` 추가 (기존 4 actor 영역 침범 없음, watchdog idle 분류 등 기존 동작 보존):

```json
{
  "be": {...},
  "fe": {...},
  "rev": {...},
  "plan": {...},
  "rev_sla": {
    "last_polled_iso": "2026-05-29T10:15:00Z",
    "open_pr_count": 7,
    "tracked": [
      {
        "pr_number": 1234,
        "pr_category": "regular|release|hotfix|security|docs",
        "stage": "1|2|3",
        "t0_iso": "2026-05-29T10:00:00Z",
        "sla_target_seconds": 1800,
        "elapsed_seconds": 900,
        "sla_met": null,
        "escalated": false,
        "escalated_at_iso": null
      }
    ],
    "escalated_recent": [
      {"pr_number": 1230, "escalated_at_iso": "2026-05-29T09:55:00Z", "channel": "digest"}
    ]
  }
}
```

근거:
- 별 sibling key — 기존 `be/fe/rev/plan` actor 영역 schema 무영향 (validate.sh / cron digest / nmae-cycle-watchdog 4중 안전망 무손상).
- `last_polled_iso` — watchdog liveness 가시화 (`tools/cycle-status/validate.sh` 확장 후보).
- `tracked` array — 현재 open PR 만 (머지 후 entry 제거). 누적 evidence 는 `rev-sla-metrics.jsonl` 책임.
- `escalated_recent` — 최근 N=10 escalation history (cron digest 표시용, jsonl 와 별도).
- `sla_met=null` — 진행 중 의미 (T0 부터 SLA target 안). `true` = 통과 / `false` = 초과 후 통과 (또는 미통과). 미통과 PR 은 `tracked` 유지.
- `tools/cycle-status/update.sh` 신규 sub-command: `rev-sla update <pr> <field>=<value>` (atomic write + validate.sh schema 가드).

**2) `rev-sla-metrics.jsonl` schema 확정** (§3-5 보강)

```jsonl
{"pr_number":1234,"stage":"1","pr_category":"regular","t0_iso":"2026-05-29T10:00:00Z","completed_iso":"2026-05-29T10:25:00Z","elapsed_seconds":1500,"sla_target_seconds":1800,"sla_met":true,"escalated":false,"escalated_at_iso":null,"escalation_channels":[],"recorded_iso":"2026-05-29T10:25:01Z","watchdog_version":"v1"}
{"pr_number":1235,"stage":"1","pr_category":"security","t0_iso":"2026-05-29T10:10:00Z","completed_iso":"2026-05-29T10:30:00Z","elapsed_seconds":1200,"sla_target_seconds":900,"sla_met":false,"escalated":true,"escalated_at_iso":"2026-05-29T10:25:00Z","escalation_channels":["digest","main_channel","user_reply"],"recorded_iso":"2026-05-29T10:30:01Z","watchdog_version":"v1"}
```

근거:
- append-only — 1 PR 1 stage 당 1 entry (PR 라이프타임 동안 최대 3 entry: 단계 1/2/3). escalation 발생 시 같은 entry 의 escalated=true 로 단계별 1줄 박제.
- `escalation_channels` array — `["digest", "main_channel", "user_reply"]` (security 분기) / `["digest"]` (정규 분기) / `[]` (escalation 없음).
- `recorded_iso` — file write 시점 (clock skew evidence).
- `watchdog_version` — schema migration 가드 (v1 default, v2 신설 시 별 키 추가).
- write 시점:
  - SLA 통과 시점 (Stage 통과 detect 직후 1회 write, completed_iso + sla_met=true)
  - escalation 발생 시점 (escalated=true write, completed_iso=null 상태로 1회 write 후 통과 시 같은 entry update — append-only 룰 위반이므로 별 entry 2개로 분리: `event="escalated"` + `event="completed"` 형태).
- 호환성: jsonl 손상 시 graceful skip (다음 polling 정상). retention §8 Q4 미결정.

**3) PR 분류 lookup 알고리즘** (§3-1 매트릭스 매핑)

`gh pr list --base develop --state open --json number,labels,createdAt,body` 출력 → 각 PR 의 `pr_category` 분류:

```text
function classify_pr(pr) {
  labels = set(pr.labels.name)

  // 우선순위 1: type:release (최강 우선)
  if "type:release" in labels:
    return ("release", "1": "exempt", "2": "24h", "3": "7d")

  // 우선순위 2: type:emergency-hotfix + security
  if "type:emergency-hotfix" in labels:
    if pr.body contains keyword in ["security", "보안", "CVE", "vulnerability"]:
      return ("security", "1": "exempt", "2": "15m", "3": "24h")
    return ("hotfix", "1": "exempt", "2": "30m", "3": "24h")

  // 우선순위 3: type:docs
  if "type:docs" in labels:
    return ("docs", "1": "5m", "2": "exempt", "3": "exempt")

  // default: 정규
  return ("regular", "1": "30m", "2": "24h", "3": "7d")
}
```

근거:
- 우선순위: release > hotfix > docs > regular (`emergency-hotfix-flow.md §3-1` 기준).
- security 분류 = `type:emergency-hotfix` AND body keyword match. body 가 없을 시 hotfix 등급 fallback.
- 다중 type:* 라벨 (예: `type:feat` + `type:docs`) 시 위 우선순위 first-match wins.
- `type:release` 단계 1 면제 = `rev-gate.yml` whitelist 와 정합 (사용자 명시 확인 강제 = §8 Q6 SoT).
- 분류 알고리즘은 cycle 마다 재계산 (캐싱 X) — PR labels 변경 시 즉시 반영.

**4) 30분 SLA timer 시작점 (T0) 정의 보강** (§3-2 상세)

`t0_iso` 결정 우선순위 (단계 1 기준):

| 단계 | T0 출처 | 정밀도 | fallback |
|---|---|---|---|
| **1순위** | `rev-queue.sh register <PR>` 호출 시 jsonl write 시각 | 초 단위 (UTC ISO) | 누락 시 2순위 |
| **2순위** | `cycle-status.json` 의 `rev.in_progress.started_at` 또는 nmae cycle event log `rev_launch_iso` | 분 단위 | 누락 시 3순위 |
| **3순위** | PR `createdAt` (GitHub API) | 분 단위 | watchdog warning + jsonl `t0_source="pr_created"` 박제 (정밀도 ↓ evidence) |

단계 2 T0 = PR `mergedAt` (1차 정확) / 단계 3 T0 = release PR `mergedAt` (`type:release` 라벨).

`elapsed_seconds` = `now() - t0_iso` (epoch second 차). watchdog polling 마다 재계산.

근거:
- 1순위 우선 — rev launch 큐 등록 시점 = 실제 rev 작업 의무 발생 시점.
- 3순위 fallback (PR createdAt) 은 정밀도 ↓ (PR 생성 후 nmae rev launch 사이 lag). `t0_source` 박제로 evidence visibility 보존.
- single source 강제 X — watchdog 가 graceful fallback 으로 모든 PR 추적 가능 (false-negative 0).

**5) 통계 집계 매트릭** (월간 회고 spec 후보 — §6 PR 7 trigger)

`rev-sla-metrics.jsonl` 으로 도출 가능한 지표:

| 지표 | 정의 | 목표값 | 알람 |
|---|---|---|---|
| **단계 1 SLA 달성률** | (sla_met=true entries) / (stage="1" entries) | ≥ 90% / 월 | < 80% = 회고 spec trigger |
| **단계 1 평균 elapsed** | mean(elapsed_seconds where stage="1") | ≤ 1200s (20m) | > 1800s (30m) = nmae 큐 race 진단 |
| **단계 1 P95 elapsed** | p95(elapsed_seconds where stage="1") | ≤ 1800s | > 3600s = 1h+ outlier 진단 |
| **escalation 발생률** | (escalated=true) / (total entries) | ≤ 10% | > 20% = watchdog 학습 의존 ↓ 추가 박제 |
| **security 분류 SLA 달성률** | (sla_met=true where pr_category="security") / (stage="2" where pr_category="security") | 100% | < 100% = 즉시 회고 (보안 사고 risk) |
| **분류별 entry 분포** | count by pr_category | regular ≥ 70% / 월 | hotfix > 20% = 안정성 회고 trigger |

집계 명령 예시 (jq 가능):

```bash
# 단계 1 SLA 달성률 (월간)
jq -s --arg month "2026-05" \
  '[.[] | select(.stage == "1" and (.recorded_iso | startswith($month)))] |
   (map(select(.sla_met == true)) | length) / length * 100' \
  ~/.mobruji/rev-sla-metrics.jsonl
```

근거:
- 회고 spec (§6 PR 7) 이 본 매트릭 6 지표를 cron 월 1회 자동 집계 → DIGEST 보고.
- 목표값 / 알람 = 초기 박제값 (운영 1개월 후 §8 Q1 따라 조정).
- 본 매트릭은 SLA 자체 박제 후 첫 회고 사이클에서 활용 — PR 3 자체는 jsonl write 만 책임.

- [ ] PR 4 (`tools/rev-queue/rev-sla.sh` self-query script): 본 spec status=approved 후. 상세 spec **§6-PR4** 박제.
- [ ] PR 5 (`06-domain-model.md §4` 보강 — `RevSlaTarget` / `RevSlaMetricEntry` / `RevSlaEscalation` / `WatchdogRevSlaLoop` 4건 등재): 본 spec status=approved 후. **부분 완료** — `RevSlaTarget` / `RevSlaEscalation` / `RevSlaWatchdog` 3건은 PR #1330 (2026-05-29 develop 머지) 으로 박제 완료. `RevSlaMetricEntry` 1건은 잔여 — `rev-sla-metrics.jsonl` schema 등재 시점 박제 (PR 3 머지 시 동시 박제 권고).
- [ ] PR 6 (`rev-e2e-3-stages.md §3` SLA cross-ref 보강): 본 spec status=shipped 후. 상세 spec **§6-PR6** 박제.
- [ ] PR 7 (월간 SLA 회고 spec 신설): SLA 적용 1개월 후

### §6-PR4) `tools/rev-queue/rev-sla.sh` self-query script 상세 spec (사전 박제)

> **목표**: PR 4 구현 시점에 plan / helper sub-agent 가 학습 의존 없이 본 sub-section 만 보고 helper script 작성 가능. CLI 인터페이스 / 출력 양식 / exit code / graceful 분기 4 항목 박제.

#### A) CLI 인터페이스

```bash
bash tools/rev-queue/rev-sla.sh <PR_number>
bash tools/rev-queue/rev-sla.sh --json <PR_number>
bash tools/rev-queue/rev-sla.sh --list-pending     # 모든 미달성 PR list
bash tools/rev-queue/rev-sla.sh --validate          # jsonl schema 검증
```

- 기본 mode: 사람-가독 텍스트 출력 (helper / fe / be sub-agent 가 자기 PR 진행 상황 확인용).
- `--json` mode: jq pipe 가능한 single-line JSON — bot.py watchdog / 후속 metrics 도구가 호출.
- `--list-pending`: SLA 미달성 PR 만 list — nmae 가 다음 사이클 우선순위 결정 시 호출.
- `--validate`: `~/.mobruji/rev-sla-metrics.jsonl` 의 schema 손상 검증 — `RevSlaWatchdog` 가 polling 직전 호출 권고.

#### B) 기본 mode 출력 양식

```text
PR #1234 (type:feat scope:web)
- 분류: 정규 type:* (단계 1 SLA = 30분)
- 큐 위치: 3 / 5
- T0 (rev-queue.sh register 시각): 2026-05-29T10:00:00Z (12 분 전)
- SLA 목표: 2026-05-29T10:30:00Z (18 분 후)
- 현재 elapsed: 12 분
- 단계 1 통과 여부: 미달 (reviewed:claude 라벨 부재)
- escalated: false
- 다음 polling 결과 (예상): SLA 안 진행 — 18 분 안 코멘트 부착 시 통과
```

미달성 PR 예시:
```text
PR #1234 (type:emergency-hotfix + body security)
- 분류: security 🔴 critical-public (단계 2 SLA = 15분)
- T0 (mergedAt): 2026-05-29T10:00:00Z (22 분 전)
- SLA 목표: 2026-05-29T10:15:00Z (7 분 초과)
- 현재 elapsed: 22 분
- 단계 2 통과 여부: 미달 (rev-post-merge-pass 라벨 부재)
- escalated: true (2026-05-29T10:16:00Z)
- escalation 채널: DIGEST + Discord 본 채널 + 사용자 reply
- 다음 polling 결과 (예상): 이미 escalated — 멱등 가드 작동
```

#### C) `--json` mode 출력 양식

```json
{
  "pr_number": 1234,
  "pr_category": "regular|release|hotfix|security|docs",
  "stage": "1|2|3",
  "sla_target_seconds": 1800,
  "t0_iso": "2026-05-29T10:00:00Z",
  "elapsed_seconds": 720,
  "remaining_seconds": 1080,
  "sla_met": null,
  "comment_attached": false,
  "label_reviewed_claude": false,
  "escalated": false,
  "escalated_at_iso": null,
  "escalation_channels": [],
  "next_polling_prediction": "in-sla-window"
}
```

- schema 는 `§3-5 RevSlaMetricEntry` 의 super-set — 추가 필드 (`remaining_seconds`, `next_polling_prediction`) 는 sub-agent self-query 용 컨텍스트.
- `next_polling_prediction` enum: `in-sla-window` / `breach-imminent` (남은 5분 미만) / `breached-pending-escalation` / `breached-escalated` / `pass`.

#### D) exit code 매트릭스

| 시나리오 | exit code | stderr |
|---|---|---|
| 정상 출력 (SLA 안 / 통과 / 미달성 + escalated) | 0 | 빈 |
| SLA 미달성 + escalation 미발사 (race condition — 다음 polling 대기) | 0 | `[warn] PR #N: SLA breached but escalation pending — watchdog next polling` |
| PR 번호 부재 (`gh pr view` 404) | 2 | `[error] PR #N not found` |
| 큐 미등록 PR (rev-queue.sh register 호출 안 된 PR) | 3 | `[error] PR #N not in rev queue — register first via rev-queue.sh register <PR>` |
| `gh` CLI 부재 / 인증 만료 | 4 | `[error] gh CLI not available — re-auth via gh auth login` |
| `rev-sla-metrics.jsonl` 부재 | 0 | `[info] jsonl not yet created — first SLA measurement` |
| `rev-sla-metrics.jsonl` 손상 (jq parse fail) | 5 | `[error] jsonl corrupted — backup + rebuild required` |
| `--validate` 통과 | 0 | `[ok] jsonl schema valid (N entries)` |
| `--validate` 실패 | 5 | `[error] jsonl schema invalid at entry M: <원인>` |

#### E) graceful skip 분기

| 분기 | 동작 |
|---|---|
| `gh pr view` rate-limited (HTTP 429) | exponential backoff (30s → 60s → 120s, max 3 retry) → 최종 실패 시 exit 4 |
| `~/.mobruji/rev-sla-metrics.jsonl` 부재 | 빈 jsonl 가정 + entry 0건으로 처리 (exit 0) — first run scenario |
| `~/.mobruji/` 디렉토리 부재 | 자동 생성 (`mkdir -p`) — daemon 부재 환경 (로컬 helper) 대응 |
| PR 라벨 / body 가 분류 매트릭스 미매칭 (예: `type:*` 부재) | "기타" 분류 — SLA = 정규 type:* 동일 적용 + stderr warning (`[warn] PR #N: type:* 라벨 부재 — 정규 SLA 기본 적용`) |

#### F) helper script 검증 절차 (PR 4 머지 후 plan / helper sub-agent 의무)

- [ ] 정상 PR 1건 (큐 등록 + SLA 안) → 기본 mode 출력 양식 §B 일치 확인
- [ ] SLA 미달성 PR 1건 + escalated=true → 기본 mode 출력에 `escalation 채널` 표기 확인
- [ ] `--json` mode 1건 → jq pipe (`bash ... --json <PR> | jq .pr_category`) 정상 동작 확인
- [ ] `--list-pending` 호출 → 미달성 PR 0건 / 1건 / N건 시나리오 각각 확인
- [ ] `--validate` 호출 → 정상 jsonl 통과 + 손상 jsonl 시뮬레이션 exit 5 확인
- [ ] PR 부재 / 큐 미등록 / `gh` CLI 부재 3 시나리오 각각 exit code + stderr 확인

검증 evidence 는 PR 4 본문 `## 검증` 섹션에 6 항목 체크박스 박제 의무 (rev 단계 1 통과 조건).

### §6-PR6) `rev-e2e-3-stages.md §3` SLA cross-ref 보강 상세 spec (사전 박제)

> **목표**: PR 6 구현 시점에 plan sub-agent 가 학습 의존 없이 본 sub-section 만 보고 docs 갱신 가능. `rev-e2e-3-stages.md` 의 어느 §3 sub-section 에 어떤 SLA cross-ref 를 박제할지 미리 결정.

#### A) `rev-e2e-3-stages.md §3-1` (PR 머지 전, 단계 1) 추가 박제

본 spec `§3-1 SLA 매트릭스` cross-ref 박제 위치 = `rev-e2e-3-stages.md §3-1` 끝 줄 (현재 `머지 게이트: 모든 PR reviewed:claude 라벨 없으면 nmae 자율 머지 안 함`) 직전.

추가할 내용 (예상 5-7 줄):
```markdown
- **응답 시간 SLA** (`docs/features/rev-sla.md §3-1` SoT — 본 spec 머지 후 PR 6 박제):
  - 정규 type:* = 30분 / `type:docs` (no-op pass) = 5분 / `type:release` = 면제 (사용자 명시 확인) / `type:emergency-hotfix` = 면제 (whitelist 머지)
  - 측정 시작 (T0) = nmae 가 rev sub-agent launch 큐 등록 시점 (`rev-queue.sh register <PR>` 호출 또는 wrapper launch 시점)
  - 측정 종료 = `reviewed:claude` 라벨 + 통과 코멘트 (✅/📝/❌) 부착 시각 중 늦은 쪽 (둘 다 만족해야 통과)
  - **차단이 아니라 가시화** — 미달성 시 `RevSlaEscalation` (`docs/features/rev-sla.md §3-4`) DIGEST push + nmae 별 rev sub-agent parallel launch trigger. 강제 머지 / 강제 회수 X.
  - 강제 메커니즘: bot.py `watchdog_rev_sla_loop` (1분 polling, `docs/features/rev-sla.md §3-3`, `nmae-cycle-watchdog.md §5-7` 5중 안전망의 5번째 layer)
```

#### B) `rev-e2e-3-stages.md §3-2` (develop 머지 후, 단계 2) 추가 박제

본 spec `§3-1 SLA 매트릭스` 단계 2 row cross-ref 박제 위치 = `rev-e2e-3-stages.md §3-2` 끝 줄 (현재 `실패 → 즉시 revert 이슈 등록 + regression:dev 라벨 + Discord push`) 직후.

추가할 내용 (예상 3-5 줄):
```markdown
- **응답 시간 SLA** (`docs/features/rev-sla.md §3-1` SoT):
  - 정규 type:* = 24시간 / `type:emergency-hotfix` (정규) = 30분 / security 🔴 critical-public (`SecurityUserNotification` 발동 PR) = 15분
  - 측정 시작 (T0) = PR 머지 완료 시점 (mergedAt, GitHub API)
  - 측정 종료 = `rev-post-merge-pass` 또는 `regression:dev` 라벨 부착 시각
  - security 🔴 미달성 분기 = DIGEST + Discord 본 채널 + 사용자 reply 3 채널 동시 push (정규 PR 은 DIGEST 1 채널만)
```

#### C) `rev-e2e-3-stages.md §3-3` (release 후, 단계 3) 추가 박제

본 spec `§3-1 SLA 매트릭스` 단계 3 row cross-ref 박제 위치 = `rev-e2e-3-stages.md §3-3` 끝 줄 (현재 `실패 → hotfix 이슈 등록 + regression:prod 라벨 + 즉시 Discord push`) 직후.

추가할 내용 (예상 2-4 줄):
```markdown
- **응답 시간 SLA** (`docs/features/rev-sla.md §3-1` SoT):
  - 정규 type:* = 7일 (단계 1 / 단계 2 보다 길게 — production 검증은 사용자 실제 사용 패턴 누적 후 의미)
  - 측정 시작 (T0) = release PR (`type:release` 라벨) 머지 시점 (mergedAt)
  - 측정 종료 = `rev-prod-pass` 또는 `regression:prod` 라벨 부착 시각
```

#### D) `rev-e2e-3-stages.md §7 관련` cross-ref 추가

본 spec link 박제 위치 = `rev-e2e-3-stages.md §7 관련` 의 이슈 list 끝.

추가할 내용:
```markdown
- spec: `docs/features/rev-sla.md` (단계별 응답 SLA + escalation, PR #1329 박제 + 본 sub-section PR 박제)
- 메모리 후보: `[[feedback-rev-sla-watchdog]]` (rev SLA 자동 escalation 사고 사례 누적 시 등재)
```

#### E) 머지 가능 시기 (PR 6 trigger)

본 spec status=shipped 시점 = PR 5 머지 (06-domain-model §4 `RevSlaMetricEntry` 마지막 1건 박제) + PR 3 머지 (`watchdog_rev_sla_loop` 본문 배포) 둘 다 완료 시점. PR 6 가 docs only 이므로 본 spec status=approved 만 만족해도 머지 가능 — 단, `docs/features/rev-sla.md` 의 §3-1·§3-3·§3-4 가 PR 1 박제로 이미 존재하므로 PR 6 는 `rev-e2e-3-stages.md` 단 1 파일 변경.

#### F) plan sub-agent 검증 절차 (PR 6 머지 후 의무)

- [ ] `rev-e2e-3-stages.md §3-1·§3-2·§3-3` 끝 줄에 SLA cross-ref 5-7 줄 / 3-5 줄 / 2-4 줄 박제 확인
- [ ] `rev-e2e-3-stages.md §7 관련` 의 spec / 메모리 후보 cross-ref 박제 확인
- [ ] `rev-e2e-3-stages.md` frontmatter `last_reviewed` 갱신 + `related_prs` 에 PR 6 번호 추가
- [ ] grep 검증: `grep -nE "docs/features/rev-sla.md" docs/features/rev-e2e-3-stages.md` 출력 ≥ 4 건 (§3-1·§3-2·§3-3·§7)

검증 evidence 는 PR 6 본문 `## 검증` 섹션에 4 항목 체크박스 박제 의무 (rev 단계 1 통과 조건).

### 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: ☐ 없음 (본 PR 한정 — docs only)
- 본 spec 후속 PR 3 (`watchdog_rev_sla_loop` 본문) 은 bot.py 변경 — 보호 영역 X 이지만 daemon 변경 가중도

## 7) 테스트 전략

### 7-1) SLA 목표값 매트릭스 검증

- §3-1 매트릭스 5 분류 각각에 대해 1 예시 PR 시나리오 작성
- 측정 시작 / 종료 시점 (§3-2) 으로 elapsed 계산 → SLA 안 / 초과 분기 확인

### 7-2) `watchdog_rev_sla_loop` polling 멱등성

- 동일 PR 의 SLA 미달성을 2회 polling → 1회 escalation 만 발생 확인 (jsonl `escalated=true` 멱등 가드)
- `rev-sla-metrics.jsonl` 손상 시뮬레이션 → graceful skip + 다음 polling 정상 동작

### 7-3) Escalation 분기 매트릭스 (§3-4)

- 5 분류 각각의 escalation 채널 동작 확인
- security 🔴 분기 — DIGEST + Discord 본 채널 + 사용자 reply 3 채널 동시 push evidence 박제

### 7-4) sub-agent self-query

- `rev-sla.sh <PR>` 호출 → 큐 위치 / T0 / SLA 목표 / elapsed / 통과 여부 정확 출력 확인
- 존재하지 않는 PR / 큐 미등록 PR 호출 → graceful error

## 8) 오픈 질문

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| ~~Q1~~ ✅ | ~~정규 단계 1 SLA 30분 — 실제 평균 응답 시간 측정 후 조정 가능. 첫 박제값 (a) 30분 / (b) 15분 / (c) 1시간~~ — **(a) 30분 채택 (1차 박제값)** (closure 2026-05-29 plan round 15). 사유 §9 결정 로그 + §8-closure-candidates §A. 운영 1개월 누적 jsonl 결과 P95 < 1200s (20m) 시 (b) 15분 조정 trigger, P95 > 2400s (40m) 시 (c) 1시간 조정 trigger — 본 조정은 §6 PR 7 (월간 회고 spec) SoT. | — | @goohong / **closed 2026-05-29 (1차 박제값, 운영 후 조정 trigger 박제)** |
| ~~Q2~~ ✅ | ~~security 🔴 단계 2 SLA 15분 — `watchdog_rev_sla_loop` 1분 polling 으로 충분 vs 30초 polling 필요~~ — **(a) 1분 polling 유지 (security 분류에도 동일)** (closure 2026-05-29 plan round 15). 사유 §9 결정 로그 + §8-closure-candidates §B. 1분 polling = 평균 30초 / 최악 60초 lag 보장 — security 15분 SLA 의 1/15 = 회귀 detect 정밀도 충분. 30초 polling 은 bot.py loop overhead 2배 + 사고 사례 누적 0건 (가설 단계) — over-engineering 회피. 실제 사고 사례 발생 시 §8-closure-candidates §B 의 trigger 매트릭스 따라 별 ADR 트리거. | — | @goohong / **closed 2026-05-29 (1분 polling 유지, 사고 사례 trigger 박제)** |
| ~~Q3~~ ✅ | ~~escalation 시 nmae 별 rev sub-agent 추가 launch — 워크트리 lock 충돌 가능 (`sub-agent.md §1-1`). (a) 같은 워크트리 다른 cycle 재사용 / (b) launch 차단 + DIGEST 만~~ — **(c) 둘 다 채택 — 우선순위 분기 (`docs/features/rev-sla.md §8-closure-candidates §C` 박제)** (closure 2026-05-29 plan round 15). 사유 §9 결정 로그 + §8-closure-candidates §C. (a) 워크트리 idle (rev 사이클 종료 / 매 cycle 끝 status=idle 박제) 시 즉시 재사용 launch / (a) 불가 시 (b) DIGEST + nmae 큐 head retag — 두 분기 모두 sub-agent.md §1-1 워크트리 lock 위반 0. parallel launch 는 별 워크트리 신설 의존 (별 spec — `rev-worktree-pool.md` 후보) 으로 위임, 본 spec 범위 X. | — | @goohong / **closed 2026-05-29 (둘 다 분기 채택, 워크트리 lock 정합)** |
| Q4 | `rev-sla-metrics.jsonl` 의 retention — 무한 누적 vs 30일 rotate | (a) 무한 / (b) 30일 rotate / (c) 월간 회고 spec 가 흡수 | @goohong / SLA 적용 1개월 후 |
| ~~Q5~~ ✅ | ~~`type:docs` 단계 1 SLA 5분 — no-op pass (📝) 자동 부착이 가능한 경우 자동화 후보 (`rev-qa-protocol.md` cross-ref 필요)~~ — **(a) 자동화 별 spec 분리 권고 + (b) 본 spec 5분 SLA 유지 병행** (closure 2026-05-29 plan round 15). 사유 §9 결정 로그 + §8-closure-candidates §D. 자동화 별 spec (`rev-docs-noop-auto.md` 후보) trigger 조건 = (1) `type:docs` 라벨 + `scope:infra` only / (2) 코드 변경 0 line (frontmatter / markdown 만) / (3) 보호 영역 파일 변경 0 — 3 조건 AND 매칭 시 bot.py 가 `📝 no-op pass` 코멘트 + `reviewed:claude` 라벨 자동 부착. 본 자동화는 별 spec 신설 후 ADR 트리거 — 본 spec §3-1 매트릭스의 5분 SLA 는 fallback 으로 유지 (자동화 실패 시 manual rev sub-agent 가 5분 안 처리). | — | @goohong / **closed 2026-05-29 (자동화 별 spec trigger 박제 + 5분 SLA fallback 유지)** |
| Q6 | release PR 단계 1 면제 — `type:release` 라벨 PR 은 사용자 명시 확인 강제로 단계 1 면제. 본 룰 prose 가 아니라 코드 강제 위치 (rev-gate.yml whitelist + watchdog skip) | (a) `nmae-cycle-watchdog.md §5-8` SoT / (b) 본 spec §3-1 매트릭스 SoT | @goohong / spec approved 전 |

### §8-closure-candidates — Q1/Q2/Q3/Q5 closure 후보 평가 매트릭스 (plan round 15)

> 본 sub-section 은 plan round 15 closure 결정의 trade-off / 채택 / 미채택 사유 + 운영 후 재검토 trigger 박제. closure 자체는 §8 표 row 의 ~~closed~~ 표기 + §9 결정 로그가 SoT — 본 sub-section 은 미래 cycle (운영 후 조정 / security 사고 / docs 자동화 별 spec) 의 first-class reference.

#### A) Q1 closure — 정규 단계 1 SLA 30분 적정성

**채택**: (a) 30분 (1차 박제값).

**3 후보 평가**:

| 후보 | trade-off (장점) | trade-off (단점) | 채택 / 미채택 사유 |
|---|---|---|---|
| **(a) 30분 (proposed, 채택)** | nmae watchdog idle 10분 (`actors/nmae.md §11-2`) 의 3배 — race condition margin 안전 / rev 큐 race 4건 동시 처리 평균 5-15분 + escalation 분기 15-25분 = 30분 안 자연 통과 / 사용자 가시화 적정 (PR push 후 30분 = 한 turn cycle 안) | 첫 박제값 — 실제 운영 evidence 부재. 큐 race 사고 frequency 높을 시 미달성률 ↑ risk | 1차 도입은 sub-agent.md §1-4 reasoning chunk 5분 + nmae cycle 평균 10-15분 (jsonl evidence: plan round 1-15 평균) 의 합리 추정. ADR-0019 정신 — agent 망각 의존 회피, jsonl evidence 누적 후 조정 |
| (b) 15분 | 사용자 가시화 정밀도 ↑ — race 미발생 시 즉시 통과 → 머지 lag ↓ | rev 큐 race 4건 동시 (head 부터 처리 평균 5-15분 × 4 = 20-60분) 시 미달성률 60%+ → escalation polling 부담 ↑ / nmae 가 큐 race 해소용 별 rev launch 빈도 ↑ → 워크트리 lock 충돌 risk ↑ (§C 분기 의존) | 운영 evidence 부재 단계 — 미달성률 60% 박제 시 jsonl noise ↑, 회고 spec 의미 ↓ |
| (c) 1시간 | 큐 race 4건 동시 처리도 자연 통과 → escalation 발생률 ≤ 5% | 사용자 가시화 정밀도 ↓ — PR push 후 1시간 = 사용자 turn cycle 2-3회 = "왜 머지 안 됨" 사고 risk ↑ / 단계 1 게이트 의미 약화 (1시간 = rev 사이클 평균 lag 와 동급) | "차단이 아니라 가시화" 정신 위반 — 본 spec §1 핵심 원칙 1 미달 |

**운영 후 조정 trigger** (§6 PR 7 월간 회고 spec SoT):

| trigger | 조정 후보 |
|---|---|
| P95 elapsed < 1200s (20m) 가 1개월 누적 | (b) 15분 으로 조정 (별 PR + ADR) |
| P95 elapsed > 2400s (40m) 가 1개월 누적 | (c) 1시간 으로 조정 또는 큐 race 해소 별 spec trigger |
| 미달성률 > 20% 가 1개월 누적 | escalation 분기 강화 (nmae 큐 race 해소 별 spec trigger) — SLA 자체 조정 X |

#### B) Q2 closure — security 🔴 단계 2 SLA 15분 polling 주기

**채택**: (a) 1분 polling 유지 (security 분류에도 동일).

**2 후보 평가**:

| 후보 | trade-off (장점) | trade-off (단점) | 채택 / 미채택 사유 |
|---|---|---|---|
| **(a) 1분 polling (proposed, 채택)** | bot.py loop overhead 적정 (`actors/nmae.md §11-1` 3중 watchdog 동일 패턴) / security 15분 SLA 의 1/15 = lag 평균 30초 / 최악 60초 = SLA 4% 초과 margin / 4중 안전망 (`nmae-cycle-watchdog.md §5-7`) loop 와 동일 주기 = bot.py 부담 균일 | 30초 단위 사고 (예: critical-public 발견 후 1분 안 머지 strictly 필요) 미보호 | security 사고 사례 0건 (가설 단계) — over-engineering 회피. 1분 polling 으로 1차 도입 후 사고 사례 누적 시 별 ADR 트리거 |
| (b) 30초 polling | security 분류만 30초 = lag 평균 15초 / 최악 30초 = SLA 2% 초과 margin | bot.py loop overhead 2배 (security 분기 별 loop 필요) / security 분류 분기 추가 = 코드 복잡도 ↑ / 본 spec §3-3 watchdog_rev_sla_loop 단일 loop 정신 위반 (4중 안전망 일관성 ↓) | security 사고 사례 0건 단계에서 over-engineering risk — 1분 polling 의 lag 60초가 critical 한 사례 박제 없음 |

**사고 사례 발생 시 trigger 매트릭스** (별 ADR 트리거 조건):

| trigger | 조정 후보 |
|---|---|
| security 분류 SLA 미달성 사고 발생 (jsonl `sla_met=false where pr_category="security"`) | 1건 = 회고 / 2건 = (b) 30초 polling 별 ADR 트리거 |
| security 분류 사고 + 사용자 reply 지연 60초 이상 ↑ noise | (b) 30초 polling 또는 별 web socket 기반 즉시 detect 별 spec |

#### C) Q3 closure — escalation 시 워크트리 lock 충돌 해소

**채택**: (c) 둘 다 채택 — 우선순위 분기.

**3 후보 평가**:

| 후보 | trade-off (장점) | trade-off (단점) | 채택 / 미채택 사유 |
|---|---|---|---|
| (a) 같은 워크트리 다른 cycle 재사용 만 | rev 워크트리 idle 시 즉시 launch = race 해소 정밀 / sub-agent.md §1-1 워크트리 lock 위반 0 (한 워크트리 = 동시 1 sub-agent) | rev 워크트리 busy 시 (현재 rev 사이클 진행 중) escalation 무동작 = 미달성 PR 머지 lag 지속 / SLA 의미 ↓ | 단독 채택 시 busy 분기 fallback 부재 — 본 spec §1 핵심 원칙 4 (강제 메커니즘) 의미 약화 |
| (b) launch 차단 + DIGEST 만 | sub-agent.md §1-1 워크트리 lock 100% 보존 / nmae 가 다음 사이클 우선순위 자율 조정 (사용자 가시화 우선) | rev 워크트리 idle 시에도 launch 안 함 = race 해소 실패 / 미달성 PR 머지 lag 지속 | 단독 채택 시 idle 워크트리 활용 0 = §1 핵심 원칙 4 의미 약화 |
| **(c) 둘 다 채택 (분기, 채택)** | rev 워크트리 status = idle (cycle-status.json 의 `rev.in_progress` null) 시 즉시 (a) launch / busy 시 (b) DIGEST + nmae 큐 head retag — 두 분기 모두 sub-agent.md §1-1 워크트리 lock 위반 0 = 코드 강제 가능 | watchdog_rev_sla_loop 의 분기 로직 추가 (cycle-status.json read 1회 추가) = bot.py 복잡도 ↑ | 단독 (a)/(b) 의 단점 모두 해소 — race 해소 정밀 + 워크트리 lock 보존 = §1 핵심 원칙 4 강제 메커니즘 정신 일치. ADR-0019 정신 (망각 가드, 코드 강제) 일치 |

**분기 로직** (PR 3 `watchdog_rev_sla_loop` 본문 박제 의무):

```text
function escalation_branch(pr):
  rev_status = read_cycle_status_json().rev.in_progress
  if rev_status == null:  # rev 워크트리 idle
    nmae_launch_rev_subagent(pr)            # (a) 분기
    log_escalation_channel(pr, "rev_relaunch")
  else:                    # rev 워크트리 busy
    discord_reply_digest("⏰ PR #{pr} rev 단계 1 SLA 미달성 ({elapsed}m elapsed) — rev 워크트리 busy, 다음 사이클 head 큐 등록")
    rev_queue_retag_head(pr)                # (b) 분기
    log_escalation_channel(pr, "digest_queue_head")
```

**parallel launch 별 spec 위임**: 별 워크트리 신설 의존 (예: `mobruji-rev-2` 워크트리 추가) 은 본 spec 범위 X — `rev-worktree-pool.md` (후보 신설 spec) 으로 위임. 본 spec 은 단일 rev 워크트리 전제 + busy 분기 가드만 책임.

#### D) Q5 closure — `type:docs` 단계 1 SLA 5분 자동화

**채택**: (a) 자동화 별 spec 분리 + (b) 본 spec 5분 SLA fallback 유지 병행.

**2 후보 평가**:

| 후보 | trade-off (장점) | trade-off (단점) | 채택 / 미채택 사유 |
|---|---|---|---|
| (a) 자동화 별 spec 만 | rev sub-agent 부담 ↓ / docs only PR 의 머지 lag 0 (자동 통과) / ADR-0019 정신 (코드 강제, 망각 가드) 일치 | 자동화 실패 시 (예: bot.py loop 죽음 / 인증 만료) docs PR 사이런스 risk — fallback 부재 | 단독 채택 시 fallback 부재 risk — 본 spec §1 핵심 원칙 4 강제 메커니즘 정신 일부 위반 |
| (b) 본 spec 5분 SLA 만 | rev sub-agent 의 manual 처리 보장 = fallback 정밀 | rev sub-agent 부담 ↑ (docs PR 빈도 ↑ 시 큐 race 가중) / 학습 의존 risk (rev 가 docs 라벨 정확히 분류 / 📝 no-op 자동 처리 의존) | 단독 채택 시 rev 큐 race 가중 risk — Q1 정규 SLA 30분 미달성률 ↑ |
| **(a) + (b) 둘 다 채택 (병행, 채택)** | 자동화 정상 시 docs PR 머지 lag 0 + rev 부담 ↓ / 자동화 실패 시 manual rev 가 5분 안 fallback 처리 = 사이런스 risk 0 | 자동화 별 spec 신설 의무 (`rev-docs-noop-auto.md` 후보) = 별 cycle 부담 / SLA 매트릭스 §3-1 row 의 5분 의미 = fallback 으로 재해석 (docs 매핑 알고리즘 §6-PR3 §3 와 정합 확인 필요) | 단독 후보의 단점 모두 해소 — 자동화 정상 + fallback 정밀 = §1 핵심 원칙 1 (목표값) + 4 (강제 메커니즘) 둘 다 만족 |

**자동화 별 spec trigger 조건** (별 spec `rev-docs-noop-auto.md` 후보 박제 시):

| 조건 (AND) | 검증 방법 |
|---|---|
| `type:docs` 라벨 부착 | `gh pr view --json labels` grep |
| `scope:*` 라벨 = `scope:infra` only (docs + 다른 scope 는 다른 매핑) | `gh pr view --json labels` grep |
| 코드 변경 0 line (frontmatter / markdown 만) | `gh pr diff --json files` 파일 path = `docs/**` 또는 `.md` 또는 `.yaml` (frontmatter) only |
| 보호 영역 파일 변경 0 | `.github/workflows/auto-label.yml` 의 정보성 분류 path 매칭 = 0 |

3 조건 AND 매칭 시 bot.py 가 자동 부착:
- `📝 no-op pass — type:docs scope:infra 자동 통과 (rev-docs-noop-auto §3-1)` 코멘트
- `reviewed:claude` 라벨

자동화 실패 시 (bot.py loop 죽음 / 인증 만료 / 매칭 분류 fail) → manual rev sub-agent fallback (5분 SLA 안 처리). watchdog_rev_sla_loop 가 5분 SLA 초과 시 escalation 분기 = §A row 4 (`type:docs` 단계 1 DIGEST).

**자동화 별 spec 신설 trigger**: 본 spec status=approved 후 별 cycle (plan / be sub-agent 협업). 본 closure 자체는 자동화 의존 X — 5분 SLA fallback 만으로 본 spec 진행 가능.

## 9) 결정 로그

- 2026-05-29: 초안 작성 (status=draft). `nmae-cycle-watchdog.md §5-7` 4중 안전망 + `rev-e2e-3-stages.md §3-1` 응답 SLA 부재 사례 박제. plan round 9 trigger.
- **2026-05-29 (plan round 12)**: **§6 PR 3 detailed design 사전 박제** — bot.py `watchdog_rev_sla_loop` 구현 launch 시 first-class reference. 5 sub-section: (1) `cycle-status.json` schema 확장 (별 sibling `rev_sla` key 추가, 기존 4 actor 영역 무영향), (2) `rev-sla-metrics.jsonl` schema 확정 (`recorded_iso` + `watchdog_version` 추가, append-only 룰), (3) PR 분류 lookup 알고리즘 (release > hotfix > docs > regular 우선순위, security = type:emergency-hotfix AND body keyword), (4) 30분 SLA timer T0 정의 (3 fallback 우선순위, `t0_source` evidence 박제), (5) 통계 집계 매트릭 6 지표 (단계 1 달성률 / 평균 elapsed / P95 / escalation 발생률 / security 100% / 분류 분포). 트리거 — plan round 12 작업 지시 + watchdog 본문 PR launch 직전 사전 spec 확정 의무. 실제 Python 코드 본문은 PR 3 본 사이클, 본 spec 은 schema / 알고리즘 / 룰만.
- 2026-05-29 (plan round 13): **§6-PR4 + §6-PR6 사전 spec 박제**. PR 4 (`rev-sla.sh` self-query script) 의 CLI 인터페이스 (4 모드) / 출력 양식 (텍스트 + JSON) / exit code 매트릭스 (9 시나리오) / graceful skip 분기 (4 케이스) / 검증 의무 (6 항목). PR 6 (`rev-e2e-3-stages.md §3` cross-ref) 의 §3-1·§3-2·§3-3·§7 박제 위치별 정확한 텍스트 prototype (5-7 / 3-5 / 2-4 / 2 줄) + 머지 가능 시기 (본 spec status=approved 만으로 가능) + 검증 의무 (4 항목). PR 5 항목 부분 완료 표기 — `RevSlaTarget` / `RevSlaEscalation` / `RevSlaWatchdog` 3건은 PR #1330 머지 완료, `RevSlaMetricEntry` 1건 잔여. 사유: PR 4 / PR 6 가 본 spec 머지 후 옵션 cycle 로 빠지면 학습 의존 risk — 사전 spec 박제로 plan sub-agent 가 본 sub-section 만 읽고 작성 가능. plan round 13 trigger.
- **2026-05-29 (plan round 15)**: **§8 Q1 / Q2 / Q3 / Q5 closure 후보 평가 사전 박제** — 4 closure 결정의 trade-off 매트릭스 / 채택 / 미채택 사유 / 운영 후 재검토 trigger 를 `§8-closure-candidates §A·B·C·D` 4 sub-section 으로 박제. 채택 요약: **Q1** = (a) 30분 (1차 박제값, P95 jsonl evidence 누적 후 조정 trigger 박제) / **Q2** = (a) 1분 polling 유지 (security 분류 사고 사례 0건 단계 over-engineering 회피, 사고 trigger 매트릭스 박제) / **Q3** = (c) 둘 다 분기 (rev 워크트리 idle → 즉시 재사용 launch / busy → DIGEST + 큐 head retag, sub-agent.md §1-1 워크트리 lock 보존, 분기 로직 prose 박제로 PR 3 watchdog_rev_sla_loop 본문 구현 의무) / **Q5** = (a) 자동화 별 spec 분리 + (b) 5분 SLA fallback 병행 (자동화 trigger 4 조건 AND 매칭 박제, 자동화 실패 시 fallback 정밀). 트리거 — plan round 15 작업 지시 + spec approved 전 4 closure 결정 의무 (§1 핵심 원칙 4 강제 메커니즘 정신). closure 매트릭스는 §8 표 row 의 ~~closed~~ 표기 + 본 항목이 SoT — `§8-closure-candidates` sub-section 은 미래 cycle (운영 후 조정 / security 사고 / docs 자동화 별 spec) 의 first-class reference. 본 closure 의 후속 spec = (Q3 의존) `rev-worktree-pool.md` (parallel launch 별 spec 신설 후보) + (Q5 의존) `rev-docs-noop-auto.md` (docs 자동화 별 spec 신설 후보) — 둘 다 별 cycle 트리거.
