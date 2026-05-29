---
feature: rev sub-agent 단계 1 응답 SLA 및 미달성 시 nmae 강제 escalation
slug: rev-sla
status: draft
owner: @goohong
scope: infra
related_issues: []
related_prs: []
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
- [ ] PR 3 (`watchdog_rev_sla_loop` 본문 신설 — bot.py + helper script): 본 spec status=approved 후 — daemon 변경 (보호 영역 X but 가중도)
- [ ] PR 4 (`tools/rev-queue/rev-sla.sh` self-query script): 본 spec status=approved 후
- [ ] PR 5 (`06-domain-model.md §4` 보강 — `RevSlaTarget` / `RevSlaMetricEntry` / `RevSlaEscalation` / `WatchdogRevSlaLoop` 4건 등재): 본 spec status=approved 후
- [ ] PR 6 (`rev-e2e-3-stages.md §3` SLA cross-ref 보강): 본 spec status=shipped 후
- [ ] PR 7 (월간 SLA 회고 spec 신설): SLA 적용 1개월 후

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
| Q1 | 정규 단계 1 SLA 30분 — 실제 평균 응답 시간 측정 후 조정 가능. 첫 박제값 (a) 30분 / (b) 15분 / (c) 1시간 | (a) / (b) / (c) | @goohong / spec approved 전 |
| Q2 | security 🔴 단계 2 SLA 15분 — `watchdog_rev_sla_loop` 1분 polling 으로 충분 vs 30초 polling 필요 | (a) 1분 / (b) 30초 | @goohong / security 사고 발생 후 |
| Q3 | escalation 시 nmae 별 rev sub-agent 추가 launch — 워크트리 lock 충돌 가능 (`sub-agent.md §1-1`). (a) 같은 워크트리 다른 cycle 재사용 / (b) launch 차단 + DIGEST 만 | (a) / (b) | @goohong / spec approved 전 |
| Q4 | `rev-sla-metrics.jsonl` 의 retention — 무한 누적 vs 30일 rotate | (a) 무한 / (b) 30일 rotate / (c) 월간 회고 spec 가 흡수 | @goohong / SLA 적용 1개월 후 |
| Q5 | `type:docs` 단계 1 SLA 5분 — no-op pass (📝) 자동 부착이 가능한 경우 자동화 후보 (`rev-qa-protocol.md` cross-ref 필요) | (a) 자동화 별 spec / (b) 본 spec 5분 SLA 유지 | @goohong / spec approved 전 |
| Q6 | release PR 단계 1 면제 — `type:release` 라벨 PR 은 사용자 명시 확인 강제로 단계 1 면제. 본 룰 prose 가 아니라 코드 강제 위치 (rev-gate.yml whitelist + watchdog skip) | (a) `nmae-cycle-watchdog.md §5-8` SoT / (b) 본 spec §3-1 매트릭스 SoT | @goohong / spec approved 전 |

## 9) 결정 로그

- 2026-05-29: 초안 작성 (status=draft). `nmae-cycle-watchdog.md §5-7` 4중 안전망 + `rev-e2e-3-stages.md §3-1` 응답 SLA 부재 사례 박제. plan round 9 trigger.
