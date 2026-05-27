---
feature: User Decisions Pending — 2026-05-26 권고안
slug: user-decisions-pending-2026-05-26
status: draft
owner: plan
scope: infra
related_issues: [1124, 1127]
related_prs: [1136, 1138]
last_reviewed: 2026-05-26
---

# User Decisions Pending — 2026-05-26 권고안 (7건)

> **Status**: draft — plan sub-agent 권고안. 사용자 결정 시 본 spec 갱신 + 후속 PR launch.
> **Owner**: mobruji plan sub-agent (rev round 4/5 audit pickup)
> **Created**: 2026-05-26
> **목적**: rev round 4/5 audit 결과 PR #1136 (redundant rules audit) + PR #1138 (cleanup policy expanded SoT) 두 PR 에 사용자 결정 대기 7건이 누적. plan 이 권고안 + trade-off + default 를 미리 제시 → helper 가 사용자 응답 처리 시 빠른 직진 (round-trip 최소화). 본 PR 자체는 **결정하지 않는다** — 권고안만.
>
> **비협상**: 본 spec 은 어떤 룰도 신설/수정/삭제하지 않음. 사용자가 항목별로 (a)/(b)/(c) 채택 또는 alternative 제시 시, **별도 후속 PR** 에서 실제 변경 (CLAUDE.md / 메모리 / sweep.sh / workflow yml) 진행.

---

## §1) 개요 (What / Why)

- **배경**: rev round 4/5 audit (2026-05-26) 에서 PR #1136 / #1138 두 spec 의 미해결 질문 합 7건 누적 — 후속 작업 (CLAUDE.md 슬림화 / agent role 강화 / cleanup 임계 결정 / cron schedule) 모두 사용자 1건 결정에 묶여 대기.
- **문제**: 사용자에게 7건을 한 번에 묻는다 = 컨텍스트 부담 + 결정 round-trip 7회 → 자율 사이클 stall.
- **해결**: plan 이 각 질문마다 (i) trade-off (ii) default 권고 (iii) 후속 액션 PR 후보 를 미리 작성. 사용자는 "Q1 (a) / Q2 default 채택 / Q3 (b) ..." 한 줄 응답만 하면 helper 가 각 PR 을 launch 가능.

### 1-1) 작업 비범위 (Out of Scope)
- 본 spec 은 결정하지 않는다 (사용자 wait state 가 의도된 정상 상태).
- 권고안은 plan sub-agent 의견 — 사용자 거부 가능.
- 후속 PR 의 실제 구현 / 메모리 갱신 본문은 별도 PR.

---

## §2) Q1 (PR #1136): release / 배포 자율 충돌 (룰 모순)

### 2-1) 충돌 본문
- **룰 A** (`tools/discord-daemon/nmae-role.md`, PR #1129 도입): "release(develop→main)·태그·배포는 사용자 결정 없이 진행 금지"
- **룰 B** (`memory/common/feedback_autonomous_default.md`): "release/destructive 포함 항상 자율"

### 2-2) trade-off

| | 룰 A 우선 (release wait) | 룰 B 우선 (release 자율) |
|---|---|---|
| 안전성 | 높음 — 사용자 가시 후 release | 낮음 — 사용자 부재 시 silent tag |
| 사용자 부담 | 중간 — release 마다 확인 필요 | 없음 |
| Hotfix 신속성 | 낮음 — 사용자 응답 wait | 높음 — nmae 즉시 tag |
| Rollback 비용 | 낮음 (release 전 차단 가능) | 높음 (irreversible — git tag, GH release 게시) |
| Audit log | 명확 (사용자 결정 기록) | 흐릿 (nmae 자율 결정 + Discord push 만) |

### 2-3) 권고: **룰 A 우선** (release / 태그 / 배포만 wait)

**이유**:
1. `git tag` + `gh release create` 는 **irreversible** — Discord 채널 / Slack / 외부 watcher 알림 발생. rollback 비용 크다.
2. 메모리 `autonomous_default` 의 "release 포함" 문구는 사용자 의도가 "사이클 launch / develop 머지 자율" 이었을 가능성 (사용자 인용 부재 — 사고 박제 X).
3. 모순 해소 형태:
   - 룰 A (nmae-role.md) 본문 유지 (canonical).
   - 룰 B (`autonomous_default.md`) 본문에 명시 예외 추가: "**release/태그/배포 제외** — 사용자 결정 필수 (§ Q1 결정)". 사고 박제 형태로 정정 인용 추가.

### 2-4) Alternative (사용자 거부 시)
- (b) 룰 B 우선 — release 도 자율 — 메모리 인용 명문화 + nmae-role.md 본문 수정.
- (c) **혼합** — develop 머지 자율, `git tag` / `gh release create` 만 wait — release CI/CD 의 phase 별로 분리.

### 2-5) 후속 PR (사용자 결정 후)
- plan 사이클: `fix(docs): autonomous_default ↔ nmae-role release-gate 모순 해소 (#1124-followup)` — 메모리 1 파일 + nmae-role.md 1 파일 + CLAUDE.md §11-X 한 줄 갱신.

---

## §3) Q2 (PR #1136): CLAUDE.md §12-1 / §13-1 슬림화 범위

### 3-1) 현재 상태
- **§12-1** (helper-role-boundary): 50+ 줄 (채널/권한/launch 표현/relay 범위/per-cycle 거울 룰). 본문 길이 큼.
- **§13-1** (sub-agent 공통 룰): 15+ 줄 (워크트리 격리 / 한 워크트리 1 sub-agent / reasoning 5분 / 메모리 직접 수정 금지 / wait state 금지 / hook 우회 / 보호 영역 / base=develop / 라벨 / 완료 보고).
- **SoT** (PR #1085 / agent role enforcement 후):
  - helper-role.md 본문 (system prompt 강제)
  - `docs/helper-rules.md` (상세 SoT)
  - `docs/ai-harness/12-sub-agent-prompt-template.md` (sub-agent SoT)

### 3-2) trade-off

| | 슬림화 (포인터 4-5줄) | 현행 유지 |
|---|---|---|
| 가독성 | 높음 — CLAUDE.md 전체 길이 ↓ | 낮음 — 본문 길이 ↑ |
| SoT 명확 | 높음 — docs 단일 진실 | 낮음 — 본문 + docs 거울 desync 위험 |
| 신규 actor 학습 | 중간 — docs read 1회 추가 | 높음 — CLAUDE.md 본문만 읽어도 OK |
| 갱신 비용 | 낮음 — docs 1곳 | 높음 — 본문 + docs 두 곳 |

### 3-3) 권고: **§12-1 핵심 4-5줄 + 상세 docs/helper-rules.md / §13-1 핵심 워크트리·lock + 상세 12-template.md**

**제안 슬림화 (§12-1)**:
```markdown
### 12-1) 채널 / 권한 경계 (상세: `docs/helper-rules.md`)

- 채널: `MOBRUJI_CHANNEL_ID` (#모부르지) 전용
- helper 본체 = 사용자 응답 + 자체 수정 (룰/CLAUDE.md/메모리/bot.py 사용자 응답 라인)
- 그 외 (PR 작업/sub-agent launch/대규모 코드) = nmae 위임 또는 helper sub-agent launch
- 표현: "launch 하겠습니다" 금지 → "nmae 에 위임하겠습니다" / "sub-agent 에 위임하겠습니다"
- 사이클 별 채널 (#모부르지-be/-fe/-rev/-plan) 직접 push 금지 — nmae / sub-agent 만 사용
```
→ 50+ 줄 → 6 줄. 본문 나머지는 `docs/helper-rules.md` 로 이전 (이미 mirror SoT).

**제안 슬림화 (§13-1)**:
```markdown
### 13-1) sub-agent 공통 룰 (상세: `docs/ai-harness/12-sub-agent-prompt-template.md`)

- 워크트리 격리 (`cd /home/mobruji/mobruji-<role>`)
- 한 워크트리 = 동시 1 sub-agent ([[feedback-worktree-lock]])
- 메모리 직접 수정 금지 (nmae 만 갱신)
- 사용자 wait state 금지 ([[feedback-sub-agent-no-user-wait]])
- `gh pr create --base develop` 강제 ([[feedback-pr-base-develop]])
- 보호 영역 변경 시 `needs-human-review` 라벨
- 완료 보고: PR URL / mergeable / 게이트 / 발견 (🔴/🟡/🟢) / 다음 후보
```
→ 15+ 줄 → 8 줄.

### 3-4) Alternative (사용자 거부 시)
- (b) **부분 슬림화** — §12-1 만 슬림 / §13-1 은 현행 유지 (또는 반대).
- (c) **유지** — 모든 본문 그대로. SoT 분산 감수.

### 3-5) 후속 PR
- plan 사이클: `refactor(docs): CLAUDE.md §12-1 / §13-1 본문 → 포인터 슬림화 (#1124-followup)`.

---

## §4) Q3 (PR #1136): `.claude/agents/<role>.md` 강화

### 4-1) 현재 상태
- PR #1135 (agent-role-enforcement) 로 4 agent definition 도입: `be.md` `fe.md` `rev.md` `plan.md`.
- 본문 평균 5-7 줄 — 핵심 boundary + 기본 룰 only.
- 예: `fe.md` 에 "npm install 금지" 한 줄 부재 — 사고 박제 메모리 (`subagent/feedback_npm_install_symlink_swap.md`) 만 가짐.

### 4-2) trade-off

| | 강화 (역할별 핵심 룰 1-3줄 추가) | 현행 (최소 본문) |
|---|---|---|
| System prompt 강제력 | 높음 | 낮음 (학습 의존) |
| Role def 가독성 | 중간 (10-15줄) | 높음 (5-7줄) |
| 메모리 의존도 | 낮음 (룰 직접 가시) | 높음 |
| 신규 actor 학습 | 빠름 | 느림 |
| Drift 위험 | 낮음 — single source | 중간 — 메모리 ↔ role def 분산 |

### 4-3) 권고: **OK — 강화**

**구체 강화안**:
- `fe.md`: "npm install 실행 금지 — 외부 디스크 symlink 보존 ([[feedback-npm-install-symlink-swap]] 사고 박제). 누락 시 nmae 보고." 한 줄 추가.
- `rev.md`: "release 전 모든 type:* PR `reviewed:claude` 라벨 필수 ([[feedback-rev-release-gate]])" + "매 사이클 첫 액션 `tools/rev-queue/rev-queue.sh all` ([[feedback-rev-e2e-always]])" 두 줄 추가.
- `be.md`: "보호 영역 (`backend/build.gradle*`, `**/db/migration/**`, `**/application*.yml`) 변경 시 `needs-human-review` 라벨" 한 줄 추가.
- `plan.md`: "프로덕션 코드 금지 (현행 유지)" + "feature spec frontmatter 의무 (`_template.md` 복제, related_prs 즉시 추가)" 한 줄 추가.

### 4-4) Alternative
- (b) **fe.md 만** — 가장 사고 박제 위험 큰 룰만 흡수, 나머지는 메모리 유지.
- (c) **거부** — role def 현행 유지, 메모리에 의존.

### 4-5) 후속 PR
- plan 사이클: `docs(infra): .claude/agents/<role>.md 강화 — 4 agent 핵심 룰 흡수 (#1124-followup)`.

---

## §5) Q4 (PR #1138): 자동 삭제 임계 (30 / 60 / 90 일)

### 5-1) 본문
- 30 일+ squash merged remote branch 자동 삭제 후보.
- 짧을수록 backlog drain 빠름, 길수록 사고 회복 윈도우 큼.

### 5-2) trade-off

| | 30 일 | **60 일** (권고) | 90 일 |
|---|---|---|---|
| Drain 속도 | 빠름 — 첫 sweep 에 backlog 90% | 중간 — 첫 sweep 에 backlog 60% | 느림 — backlog 30% |
| 사고 회복 윈도우 | 좁음 (1 sprint) | 중간 (2 sprint) | 넓음 (3 sprint) |
| stale 누적 위험 | 낮음 | 중간 | 높음 |
| 활성 PR 충돌 | 가능 (긴 PR) | 낮음 | 매우 낮음 |

### 5-3) 권고: **60 일**

**이유**:
1. 활성 PR 전형적 sprint = 2 주, 4 sprint = 8 주 = 약 60 일. 60 일 미활성 = 2 sprint 이상 미접촉 = stale 합리.
2. 30 일은 활성 PR 의 review/merge cycle (특히 release 전 hold) 와 충돌 가능 — 예: hotfix branch 가 30 일+ 대기.
3. 90 일은 backlog drain 너무 느림 — 451 branch → 30% drain (300 잔존) → 적체 해소 효과 약함.
4. PR #1138 본문은 default 30 일 — 사용자 정정 의도 가능. 60 일이 보수적 + 효과 양립.

### 5-4) Alternative
- (a) 30 일 — PR #1138 default 채택, 적극 drain.
- (c) 90 일 — 보수적, 1 차 운영 후 점진 단축.

### 5-5) 후속 PR
- be 사이클: `feat(infra): branch-cleanup sweep.sh 자동 삭제 임계 60 일 적용 (#1127-followup)` — `tools/branch-cleanup/sweep.sh` 의 `MERGE_DAYS_THRESHOLD=60` 적용.

---

## §6) Q5 (PR #1138): closed-not-merged branch 처리

### 6-1) 본문
- PR 가 closed 됐지만 merged=false (기각/abandoned) 인 branch 처리.
- 즉시 삭제 vs 보존.

### 6-2) trade-off

| | 즉시 삭제 | **30 일 grace period + 라벨** (권고) | 영구 보존 |
|---|---|---|---|
| Cleanup 효과 | 높음 | 중간 | 없음 |
| 사용자 retry 의도 보존 | 위험 | 안전 (30 일 retry 가능) | 안전 |
| Audit 추적 | 어려움 (삭제 후 추적 X) | 중간 (라벨 trace) | 쉬움 |
| 운영 비용 | 낮음 | 중간 | 높음 (backlog 누적) |

### 6-3) 권고: **30 일 grace period + `closed:not-merged:30d-stale` 라벨 → 30 일 후 자동 삭제**

**이유**:
1. closed-not-merged 는 사용자가 의도적으로 close 한 경우 / 충돌로 포기한 경우 등 다양 — 즉시 삭제는 사용자 retry 의도 무시.
2. 30 일 grace = retry / 다른 branch 로 cherry-pick 시간 제공.
3. 라벨 부착 = audit log + sweep 후보 명시 가시화.
4. 30 일 + Q4 (60 일 권고) 와 일관성 — closed-not-merged 가 merged 보다 짧은 grace 인 이유는 "재사용 가능성 ↓" 가정.

### 6-4) Alternative
- (a) 즉시 삭제 — 사용자가 close 한 시점 = 명시적 의도 → 삭제 OK.
- (c) Q4 와 동일 (60 일) — 일관성 우선.

### 6-5) 후속 PR
- be 사이클: `feat(infra): closed-not-merged branch grace 30 일 + 라벨 + sweep (#1127-followup)`.

---

## §7) Q6 (PR #1138): closed issue stale 임계

### 7-1) 본문
- 30 일+ closed GitHub issue 에 `stale-cleanup-YYYY-QN` 라벨 자동 부착 임계.

### 7-2) trade-off

| | 30 일 | 90 일 | **180 일** (권고) |
|---|---|---|---|
| 라벨 부착량 | 매우 많음 (416 건 즉시) | 많음 | 적음 (오래된 것만) |
| reopened 가능성 | 높음 (1-3 개월) | 중간 | 낮음 (6 개월+ 재오픈 드뭄) |
| 검색 노이즈 ↓ | 큼 | 중간 | 작음 |
| Quarterly 추적 | 어려움 | 중간 | 쉬움 (quarterly = 90 일이라 180 = 2분기) |

### 7-3) 권고: **180 일**

**이유**:
1. closed issue 는 branch 와 달리 **GitHub 영구 보존** — 삭제 위험 X. 라벨링 = 검색 결과 dominance 완화 목적만.
2. 30 일은 너무 짧음 — closed → reopened 가 1-3 개월 안에 흔함 (사용자 follow-up / 신규 정보).
3. 180 일 = 2 sprint quarter (90 일 × 2) — 분기 단위 backlog drain 자연 정렬.
4. branch 임계 (Q4 60 일) 와 다른 이유: branch 는 git ref + fetch latency 영향 → 빠른 정리 우선. issue 는 영구 보존 + 검색 영향만 → 보수적 OK.

### 7-4) Alternative
- (a) 30 일 — Q4 와 일관성, 적극 라벨링.
- (b) 90 일 — quarterly 단일.
- (d) Q4 와 동기화 (60 일) — 모든 stale 임계를 60 일로 통일.

### 7-5) 후속 PR
- be 사이클: `feat(infra): closed issue stale-cleanup-YYYY-QN 라벨 180 일 임계 (#1127-followup)`.

---

## §8) Q7 (PR #1138): cron schedule 시각

### 8-1) 본문
- 자동 cleanup workflow cron schedule.
- PR #1138 default: monthly 1 일 09:00 KST, `workflow_dispatch` only (cron auto-trigger 금지).

### 8-2) trade-off

| | **Monthly 1 일 09:00 KST + dispatch only** (권고) | Cron 자동 trigger | Release tag 직전 1 회 |
|---|---|---|---|
| 사고 회복 | 높음 (dispatch 통제) | 낮음 (자동 실행) | 중간 |
| 정기성 | 높음 (월간 신뢰) | 높음 | 낮음 (release 빈도 의존) |
| 사용자 부담 | 중간 (월 1회 click) | 없음 | 낮음 |
| nmae 자동화 | 어려움 (manual 1회) | 쉬움 | 중간 (release tag 시 자동) |

### 8-3) 권고: **PR #1138 default 채택 — monthly 1 일 09:00 KST workflow_dispatch only**

**이유**:
1. 사용자가 PR #1138 본문에 명시한 default — 의도 명확.
2. `--apply` 자동 trigger 금지 = 사고 회복 가능성 우선 (irreversible delete 회피).
3. monthly 1 일 = 분기/연간 backlog drain natural rhythm.
4. `workflow_dispatch` = nmae 가 자동 trigger 못 함 → 사용자 통제 명확 (Q1 release-gate 일관성).

### 8-4) Alternative
- (b) cron 자동 trigger — dispatch 부담 제거. 단 사고 회복 윈도우 ↓.
- (c) release tag 직전 1 회 — release 시 자연 drain. 단 release 안 하는 sprint 누적.
- (d) 병행 — monthly + release tag 직전.

### 8-5) 후속 PR
- be 사이클: `feat(infra): .github/workflows/branch-cleanup-monthly.yml 신설 (#1127-followup)` — `needs-human-review` 라벨 (보호 영역).

---

## §9) 권고 요약 (사용자 1줄 응답용)

| # | 질문 | 권고 default | Alternative |
|---|---|---|---|
| Q1 | release 자율 충돌 | **룰 A 우선** (release/tag wait) | (b) 룰 B / (c) 혼합 |
| Q2 | CLAUDE.md §12-1/§13-1 슬림화 | **포인터 슬림화 (§12-1: 6줄, §13-1: 8줄)** | (b) 부분 / (c) 유지 |
| Q3 | `.claude/agents/<role>.md` 강화 | **OK — 4 agent 모두 1-3줄 추가** | (b) fe만 / (c) 거부 |
| Q4 | 자동 삭제 임계 (merged) | **60 일** | (a) 30일 / (c) 90일 |
| Q5 | closed-not-merged 처리 | **30 일 grace + 라벨** | (a) 즉시 / (c) 60일 |
| Q6 | closed issue stale 임계 | **180 일** | (a) 30일 / (b) 90일 / (d) 60일 |
| Q7 | cron schedule | **PR #1138 default (monthly 1일 09:00 + dispatch only)** | (b) 자동 / (c) release / (d) 병행 |

### 9-1) 사용자 응답 예시 (helper 빠른 직진)

응답 A (모두 default 채택):
> "Q1-Q7 모두 default 권고 채택"
→ helper 가 7개 후속 PR 을 nmae/be/plan 분담 launch.

응답 B (일부 정정):
> "Q1 (c) 혼합 / Q4 30일 / 나머지 default"
→ Q1, Q4 만 정정. 5건은 default.

응답 C (한 건 추가 논의):
> "Q3 거부 — agents/<role>.md 본문 짧게 유지 / 나머지 default"
→ Q3 외 6건 진행.

---

## §10) 후속 액션 PR 분담 (7건 전부 default 가정)

### plan 사이클 (3 PR)
1. **Q1 후속**: `fix(docs): autonomous_default ↔ nmae-role release-gate 모순 해소`
   - 파일: `memory/common/feedback_autonomous_default.md`, `tools/discord-daemon/nmae-role.md`, `CLAUDE.md §4`.
2. **Q2 후속**: `refactor(docs): CLAUDE.md §12-1 / §13-1 포인터 슬림화`
   - 파일: `CLAUDE.md`.
3. **Q3 후속**: `docs(infra): .claude/agents/<role>.md 강화 — 4 agent 핵심 룰 흡수`
   - 파일: `.claude/agents/{be,fe,rev,plan}.md`.

### be 사이클 (4 PR)
4. **Q4 후속**: `feat(infra): branch-cleanup sweep.sh 자동 삭제 임계 60 일 적용`
   - 파일: `tools/branch-cleanup/sweep.sh`.
5. **Q5 후속**: `feat(infra): closed-not-merged branch grace 30 일 + 라벨 + sweep`
   - 파일: `tools/branch-cleanup/sweep.sh`, GitHub label 생성.
6. **Q6 후속**: `feat(infra): closed issue stale-cleanup-YYYY-QN 라벨 180 일 임계`
   - 파일: `tools/branch-cleanup/issue-stale.sh` (신규).
7. **Q7 후속**: `feat(infra): .github/workflows/branch-cleanup-monthly.yml 신설`
   - 파일: `.github/workflows/branch-cleanup-monthly.yml`. **`needs-human-review` 라벨 의무** (보호 영역).

총 7 PR. plan 3 / be 4 분담.

---

## §11) 검증 / 후속 사이클 관찰

### 11-1) 본 spec 머지 후
1. helper 가 사용자에게 §9 요약 표 제시 (Discord 1 메시지).
2. 사용자 응답 (한 줄 패턴 — 예시 A/B/C).
3. helper 가 응답 기반 후속 PR launch 분담 (plan/be) — nmae 위임.

### 11-2) 7건 결정 적용 후 (1-2 주 후)
- CLAUDE.md 본문 길이 측정 (슬림화 효과).
- branch 수 변화 (cleanup 효과).
- 사이클 룰 위반 발생 빈도 (agent role 강화 효과).

### 11-3) drift 감지
- 메모리 ↔ role def desync 발생 시 다음 audit 사이클 (round 6+) 에서 재확인.
- §15 doc-check (helper /clear 직전) 가 4-way 일치 검증.

---

## §12) 변경 이력

- 2026-05-26: 신규 작성 (PR docs/user-decisions-pending-spec, plan sub-agent rev round 4/5 audit pickup). status=draft, 사용자 결정 대기.
