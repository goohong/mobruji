---
feature: rev-gate-audit workflow (post-merge bypass 감사)
slug: rev-gate-audit-workflow
status: draft
owner: @goohong
scope: infra
related_issues: []
related_prs: [1322]
last_reviewed: 2026-05-29
---

# rev-gate-audit workflow (post-merge bypass 감사)

## 1) 개요 (What / Why)

`rev-gate.yml` workflow + `enforce_admins=true` branch protection 로 사전 차단이 1차 라인이고, 본 spec 의 `rev-gate-audit.yml` workflow 는 **그 사전 차단이 우회 / race window / whitelist 면제 등으로 통과한 머지에 대해 사후 evidence 를 박제** 하는 2차 라인이다. 자율 사이클 throughput 을 깎지 않으면서도 "모든 머지에 대해 rev 통과 evidence 가 머지 시점에 존재했는가" 를 100% 추적 가능하게 한다.

본 spec 은 PR #1322 (`rev-gate-required-check-enforcement`) §3-3 의 후속 — 사전 차단 강화 spec 안 "post-merge audit hook" 한 항목만 떼어 workflow 본문 / 트리거 / output / 부수 효과를 상세화한다.

트리거 사고: PR #1318 (2026-05-29) admin override merge — `enforce_admins=false` 우회 + `reviewed:claude` 사후 부착 (3분 차) + head commit check-runs 0건. 사전 차단을 강화하더라도 emergency-hotfix / whitelist / race window 잔존 → 사후 감사 채널 필수.

## 2) 사용자 시나리오

### 2-1) 정상 머지 (audit pass)

- PR 머지 → `rev-gate-audit.yml` trigger
- workflow 가 mergedAt vs `reviewed:claude` labelEvent.createdAt 비교 → label 이 더 이르면 audit pass
- check-run `rev-gate-audit` = green, 부수 효과 없음 (silent pass)

### 2-2) emergency-hotfix 머지 (whitelist pass + 사후 의무)

- `type:emergency-hotfix` 라벨 PR 머지 → audit workflow 안에서 whitelist 매칭
- check-run `rev-gate-audit` = green (whitelist 사유 명시)
- **사후 부수 효과 강제**: `audit:emergency-hotfix-followup` 라벨 issue 자동 생성 + DIGEST push (사용자 알림)
- 본 issue 에 rev 사이클이 사후 단계 2 실행 → 통과 시 issue close

### 2-3) bypass 감지 (audit fail)

- `reviewed:claude` 부재 + whitelist 부재로 머지 발생 — branch protection 우회 (예: protection 일시 비활성) 또는 외부 push
- workflow audit fail → check-run `rev-gate-audit` = failure (red)
- DIGEST 채널 push + issue 자동 생성 (`audit:rev-gate-bypass` 라벨, body 에 commit / PR / mergedBy / mergedAt 박제)
- nmae 가 issue queue 발견 → 분류 후 plan 사이클 launch 또는 사용자 보고

## 3) 요구사항

### 기능 요구사항

#### 3-1) workflow trigger

- [ ] `.github/workflows/rev-gate-audit.yml` 신설
- [ ] `on.pull_request_target.types: [closed]` (보안: `pull_request_target` 필요 — write 권한 + secret 접근)
- [ ] `jobs.audit.if: github.event.pull_request.merged == true` — close-without-merge skip
- [ ] permissions 최소화: `pull-requests: write` (label / comment) + `issues: write` (issue 생성) + `checks: write` (check-run) + `contents: read`

#### 3-2) audit 단계 (workflow steps)

- [ ] Step 1: PR metadata fetch (`gh pr view ${{ github.event.pull_request.number }} --json mergedAt,labels,timelineItems`)
- [ ] Step 2: whitelist 매칭 (라벨 검사)
  - `type:release` → audit skip (release PR 은 develop → main 머지로 사용자 명시 확인 + 본 audit 도 skip)
  - `type:emergency-hotfix` → whitelist pass (단, §3-3 부수 효과 트리거)
  - 그 외 → §3-3 정상 검사로 진행
- [ ] Step 3: `reviewed:claude` label 부착 시각 확인
  - timelineItems 에서 `LabeledEvent` + `label.name == "reviewed:claude"` 중 가장 이른 `createdAt`
  - 비교: `labeledAt < mergedAt` 이면 1 차 통과
- [ ] Step 4: rev 통과 코멘트 확인
  - PR 코멘트 본문 grep — `✅ rev e2e PR pass` / `📝 rev no-op pass` 중 하나라도 `createdAt < mergedAt` 이면 통과
  - 코멘트 search pattern (정규식): `^(✅ rev e2e PR pass|📝 rev no-op pass)`
- [ ] Step 5: 두 조건 (label + 코멘트) 모두 통과 → audit pass / 하나라도 부재 → audit fail

#### 3-3) audit 결과별 부수 효과

##### 3-3-1) audit pass (정상)

- check-run 신설: `name=rev-gate-audit`, `conclusion=success`, `output.title="rev-gate audit pass"`, `output.summary` 에 label/comment evidence 박제
- 부수 효과 없음 (silent)

##### 3-3-2) emergency-hotfix whitelist pass

- check-run `name=rev-gate-audit`, `conclusion=success`, `output.title="emergency-hotfix whitelist pass"`
- GitHub issue 자동 생성:
  - title: `[audit][emergency-hotfix-followup] PR #${{ pr.number }} 사후 rev 🔵 Post-merge audit (단계 2) 의무`
  - labels: `audit:emergency-hotfix-followup`, `type:chore`, `scope:infra`
  - body: PR 링크 / mergedAt / mergedBy / merge commit SHA / 사후 rev 🔵 Post-merge audit (단계 2) 절차 (`docs/features/rev-e2e-2-stages.md §3-2`) 참조
- DIGEST push (`tools/discord-daemon/discord-reply.sh --digest` 호출 또는 workflow 안에서 webhook 직접 호출 — §5-3 선택지 기재)

##### 3-3-3) audit fail (bypass 감지)

- check-run `name=rev-gate-audit`, `conclusion=failure`, `output.title="rev-gate bypass detected"`
- GitHub issue 자동 생성:
  - title: `[audit][rev-gate-bypass] PR #${{ pr.number }} 머지 시점 rev evidence 부재`
  - labels: `audit:rev-gate-bypass`, `type:fix`, `scope:infra`
  - body: PR 링크 / mergedAt / mergedBy / merge commit SHA / 검사 결과 (label 부재 / 코멘트 부재 / whitelist 부재) / 사후 절차 (rev 사이클 launch + 회귀 검사)
- DIGEST push (사용자 즉시 가시화)
- PR 본문에 audit fail 알림 코멘트 append (`audit:rev-gate-bypass — issue #${{ issue.number }}` 링크)

#### 3-4) idempotency / 재실행 가드

- [ ] workflow 재실행 시 동일 PR 에 대해 issue 중복 생성 금지
  - issue search: `label:audit:rev-gate-bypass in:title PR #${{ pr.number }}` 이미 존재하면 skip + 기존 issue body 에 재실행 timestamp 추가
- [ ] check-run 은 마지막 결과로 overwrite (GitHub Checks API 기본 동작)

### 비기능 요구사항

- **관측성**: workflow 실행 결과는 GitHub Actions 탭 + check-run + DIGEST 채널 + issue 4 채널 가시화
- **신뢰성**: PR fetch / issue 생성 등 GitHub API 호출은 1회 재시도 (transient 502 대비). 최종 실패 시 workflow fail 로그만 남기고 silent skip 금지 — DIGEST push 라도 시도
- **회복성**: workflow 자체가 fail 했을 때 (예: API rate limit) — GitHub Actions retry 1회 + 그래도 실패 시 다음 머지가 들어오는 시점에 backlog audit 수행 (별 spec 후보, §8 Q4)
- **보안**: `pull_request_target` 사용 시 외부 fork PR 의 코드 실행 risk — workflow 본문에서 `actions/checkout` 호출 금지 + PR head SHA 의 코드는 신뢰하지 않음 (metadata 만 사용). GitHub 공식 권고 준수
- **성능**: 1회 audit 실행 시간 30초 이하 목표 (GitHub API 호출 3~5회). 1일 머지 50건 가정 시 GHA 분당 사용량 < 25분 (free tier 안)
- **horizontal scalability**: 동시 머지 (예: 5건 동시 머지) 발생 시 workflow 각각 독립 실행 — race 없음 (GitHub 보장)

## 4) 범위 / 비범위

### 포함

- `rev-gate-audit.yml` workflow 본문 spec (event / job / step / output)
- 3 결과 분기 (audit pass / emergency-hotfix whitelist / bypass fail) 의 부수 효과 박제
- check-run / issue / DIGEST 3 채널 알림 spec
- idempotency 가드 spec (issue 중복 생성 차단)

### 제외 (Out of Scope)

- branch protection 변경 자체 — PR #1322 (`rev-gate-required-check-enforcement`) §3-1 분리
- `rev-gate.yml` 본문 수정 — PR #1322 §3-4 분리 (closed type 추가, whitelist 추가)
- emergency-hotfix 라벨 생성 / 사용법 가이드 — 본 spec 의 별 PR (작업 B, `emergency-hotfix-flow` spec) 분리
- audit fail issue 가 자동 fix PR 까지 생성하는 시나리오 (auto revert) — PR #1322 §8 Q4 결정 후
- 옛 PR retro audit (본 spec 적용 시점부터)
- workflow 본문 yml 실제 작성 — 본 spec accepted 후 별 PR (§6 PR 2)

## 5) 설계

### 5-1) 도메인 모델

- 기존: `ReviewedClaudeLabel`, `RevGateCheck` (06-domain-model.md §4 등재)
- 신설 후보 (본 spec accepted 시 등재):
  - `RevGateAuditCheck` — `rev-gate-audit` check-run 자체
  - `BypassAuditIssue` — `audit:rev-gate-bypass` 라벨 issue
  - `EmergencyHotfixFollowupIssue` — `audit:emergency-hotfix-followup` 라벨 issue
- 신설 라벨 후보 (gh label create):
  - `audit:rev-gate-bypass` (color `D73A4A` — fix 동일 계열)
  - `audit:emergency-hotfix-followup` (color `FBCA04` — chore 계열)

### 5-2) workflow yml 골격 (참조용 — 실제 작성은 별 PR)

```yaml
name: rev-gate-audit
on:
  pull_request_target:
    types: [closed]

permissions:
  pull-requests: write
  issues: write
  checks: write
  contents: read

jobs:
  audit:
    if: github.event.pull_request.merged == true
    runs-on: ubuntu-latest
    steps:
      - name: Fetch PR metadata
        id: meta
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          gh pr view ${{ github.event.pull_request.number }} \
            --repo ${{ github.repository }} \
            --json mergedAt,labels,timelineItems > pr.json
      - name: Evaluate whitelist
        id: whitelist
        run: |
          # type:release → skip / type:emergency-hotfix → whitelist
      - name: Evaluate label + comment evidence
        id: evidence
        if: steps.whitelist.outputs.kind == 'none'
        run: |
          # reviewed:claude labeledAt < mergedAt && comment exists?
      - name: Create check-run (pass / fail)
        run: |
          # POST /repos/.../check-runs
      - name: Create issue + DIGEST (audit fail or emergency-hotfix)
        if: steps.whitelist.outputs.kind == 'emergency' || steps.evidence.outputs.result == 'fail'
        run: |
          # gh issue create + curl DIGEST webhook
```

본 골격은 spec 박제용 — 실제 workflow 본문 (env / secrets / 모든 step 본문) 은 §6 PR 2 에서 작성.

### 5-3) 외부 연동

- **GitHub REST API**: PR fetch, label fetch, timeline, check-run create, issue create
- **DIGEST push 채널 선택지**:
  - (a) `tools/discord-daemon/discord-reply.sh --digest` — workflow 안에서 ssh / 직접 호출 어려움 (helper 머신 NCP 환경 의존). pass.
  - (b) Discord webhook URL 직접 호출 (`secrets.DISCORD_DIGEST_WEBHOOK`) — workflow 단독 자율 가능. **본 spec 채택 후보**.
  - (c) repository_dispatch event → nmae watchdog 가 polling 후 push — 간접 경로. 지연 risk.
  - **§8 Q1** 으로 오픈 (사용자 선호 confirm 필요)
- **권한**: workflow 의 `GITHUB_TOKEN` 으로 issue / check-run / label 모두 가능. 추가 PAT 불요

### 5-4) 데이터 흐름 / 시퀀스

```text
[1] PR mergedAt event → GHA dispatcher → rev-gate-audit.yml
[2] step: gh pr view → labels + timelineItems + mergedAt fetch
[3] step: whitelist check
    ├ type:release → skip + check-run skipped status
    ├ type:emergency-hotfix → §3-3-2 분기 (whitelist pass + 사후 의무 issue)
    └ none → §3-3-3 분기 정상 검사로 진행
[4] step: label + comment 검사
    ├ reviewed:claude labeledAt < mergedAt? AND
    └ comment grep '^(✅ rev e2e PR pass|📝 rev no-op pass)' exists < mergedAt?
[5-pass] check-run rev-gate-audit success + silent
[5-emergency] check-run success + issue (audit:emergency-hotfix-followup) + DIGEST
[5-fail] check-run failure + issue (audit:rev-gate-bypass) + DIGEST + PR comment
```

### 5-5) DB 마이그레이션

해당 없음 (infra-only).

### 5-6) 프론트엔드 화면

해당 없음.

## 6) 작업 분할 (예상 PR 리스트)

- [ ] PR 1 (본 spec 박제): docs only — 본 PR
- [ ] PR 2 (workflow 본문 작성): `.github/workflows/rev-gate-audit.yml` + 라벨 2개 (`audit:rev-gate-bypass`, `audit:emergency-hotfix-followup`) gh label create. spec accepted (status=approved) 후 launch
- [ ] PR 3 (DIGEST 채널 검증): workflow 본문 deploy 후 일부러 audit fail / emergency-hotfix 시나리오 1회 trigger → DIGEST push 도착 확인 + issue 생성 확인 → 본 spec status=shipped 갱신
- [ ] PR 4 (idempotency 회귀 테스트): workflow 동일 PR 에 대해 재실행 → issue 중복 생성 차단 확인

### 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: ☑ 있음 — 변경 파일과 사유:
  - `.github/workflows/rev-gate-audit.yml` (신설) — post-merge audit 강제 메커니즘
- rev 단계 1 audit 시 본 항목 가중도 ↑ (신규 workflow 추가 — 회귀 시 영향 범위 평가 필요)

## 7) 테스트 전략

### 7-1) audit pass 시나리오 (정상)

- `docs/features/_template.md` 갱신 같은 trivial PR 1건 → rev no-op pass 정상 절차로 머지 → check-run `rev-gate-audit` green 확인

### 7-2) emergency-hotfix 시나리오

- `type:emergency-hotfix` 라벨 PR 1건 생성 (실제 production 사고 아닌 dry-run)
- `rev-gate.yml` 의 whitelist (PR #1322 §3-2) 가 skip 했는지 확인 → audit workflow 의 whitelist 분기 진입
- issue 자동 생성 + DIGEST push 도착 확인 → 본 issue close 절차 박제

### 7-3) bypass 시나리오 (audit fail)

- `enforce_admins=true` 가 정상이라면 bypass 자체 불가 → 인위적 시뮬레이션 어려움
- 대안: 머지 후 즉시 `reviewed:claude` 라벨 제거 → workflow 재실행 trigger 가능한지 확인
  - 불가능 → workflow 자체 unit test 환경에서 fixture 로 검증 (별 spec 후보)
- `rev-gate.yml` whitelist 우회 (예: 옛 PR 의 `type:release` 라벨 제거 후 머지) — 정책상 금지 (회귀 risk)

### 7-4) idempotency 테스트

- 동일 PR 의 workflow 강제 재실행 → issue 추가 생성 X + 기존 issue body 에 timestamp 추가 확인

## 8) 오픈 질문

> 구현 전에 답이 나와야 하는 것들. 해소되면 §9 결정 로그로 이동.

| # | 질문 | 선택지 | 담당 / 기한 |
|---|---|---|---|
| Q1 | DIGEST push 채널 구현 방법 | (a) discord-reply.sh ssh 호출 (NCP 의존) / (b) Discord webhook URL secret 직접 호출 / (c) repository_dispatch → nmae polling | @goohong / PR 2 launch 전 |
| Q2 | emergency-hotfix 라벨 사용 빈도 임계치 | (a) 월 1회 (경고만) / (b) 월 3회 (audit 라벨 red dot) / (c) 무제한 (모든 사용을 issue 박제) | @goohong / PR 2 launch 전. PR #1322 §8 Q2 와 sync |
| Q3 | audit fail issue 자동 close 조건 | (a) 사후 rev 단계 2 통과 + 사용자 ack / (b) revert PR 머지 / (c) 수동 close 만 | @goohong / PR 3 launch 전 |
| Q4 | workflow 자체 fail (transient) 시 backlog audit 전략 | (a) 다음 머지가 들어오는 시점에 backlog scan / (b) cron schedule 별 spec / (c) workflow retry 만 (1회) | plan / 별 spec 후보 |
| Q5 | check-run 의 `head_sha` — merge commit SHA vs PR head SHA | (a) merge commit (정확한 머지 시점) / (b) PR head SHA (rev-gate.yml 와 동일 위치) | @goohong / PR 2 launch 전 |

## 9) 결정 로그

> 연대기 순. "YYYY-MM-DD: 결정 / 이유 / 출처(PR 번호 등)"

- **2026-05-29**: 초안 작성 (status=draft). 트리거 — PR #1322 (`rev-gate-required-check-enforcement`) §3-3 의 후속 분리. plan round 8 작업. 사전 차단 (branch protection / rev-gate.yml) 과 사후 감사 (본 workflow) 를 분리 spec 으로 박제하여 책임 경계 명확화 + 작업 PR 단위 분할.
- **2026-05-29**: §3-3-2 emergency-hotfix 분기 — 우회 자체를 차단하지 않고 "사후 의무 issue 강제" 로 합리화. PR #1322 §3-2 의 정책 (사후 rev 단계 2 의무 트리거) 과 sync.
- **2026-05-29**: §3-3-3 bypass 분기 — DIGEST + issue 2 채널 동시 push 채택. 사용자 가시화 [[feedback-helper-thread-usage]] 일관성.
- **2026-05-29**: §3-4 idempotency — workflow 재실행 시 issue 중복 생성 차단 + 기존 issue 에 timestamp 추가. nmae backlog 노이즈 ↓.
- **2026-05-29**: §5-3 DIGEST push 구현 방법은 §8 Q1 으로 오픈 — webhook URL secret 채택이 자율 사이클 안에서 단독 가능 (NCP ssh 의존 제거) 한 점에서 후보 1순위. 사용자 confirm 필요.
