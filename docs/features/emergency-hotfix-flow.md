---
feature: type:emergency-hotfix 라벨 + 합법 우회 hotfix 흐름
slug: emergency-hotfix-flow
status: draft
owner: @goohong
scope: infra
related_issues: []
related_prs: [1322]
last_reviewed: 2026-05-29
---

# type:emergency-hotfix 라벨 + 합법 우회 hotfix 흐름

## 1) 개요 (What / Why)

PR #1322 (`rev-gate-required-check-enforcement`) 가 `develop` `enforce_admins=true` + `main` branch protection 신설로 사전 머지 차단을 강화하면, P0 (production down / security 즉시 패치) 상황에서 rev 사이클 launch 대기 시간 (분~수십분) 이 user impact 를 키운다. 본 spec 은 **합법 우회 경로** 로 `type:emergency-hotfix` 라벨을 도입하여 즉시 머지 가능하게 하되, 사후 감사 + 회귀 검증 + 사용 빈도 추적 의무를 워크플로 + audit hook 로 강제한다.

핵심 원칙: **차단 우회를 금지하지 말고, 우회 경로를 합법화하고 사후 박제하라.** ([[feedback-evidence-based-root-cause]] §17 — 강제 메커니즘 우선, 메모리 학습 보조).

본 spec 은 PR #1322 §3-2 (emergency hotfix 절차) + PR #1324 (`rev-gate-audit-workflow`) §3-3-2 (whitelist pass 분기) 의 운영 가이드 / 라벨 정책 / 사후 의무를 한 곳에 통합 박제한다.

## 2) 사용자 시나리오

### 2-1) production 장애 hotfix (정상 emergency 흐름)

- production 에서 사용자 음역대 입력 API 가 500 응답 (사고 발생 시각 T0)
- nmae 또는 사용자가 root cause 빠르게 추정 → backend sub-agent launch
- backend sub-agent: fix 작성 → PR 생성 → `type:emergency-hotfix` 라벨 부착 (`gh pr edit ... --add-label type:emergency-hotfix`)
- `rev-gate.yml` whitelist (PR #1322 §3-2) 매칭 → workflow skip → PR mergeable
- nmae 또는 사용자가 즉시 머지 (T0 + 10~30분 안)
- `rev-gate-audit.yml` (PR #1324) 가 머지 trigger → emergency-hotfix whitelist 분기 → 자동 issue 생성 (`audit:emergency-hotfix-followup`) + DIGEST push
- nmae 가 다음 사이클 안에 사후 rev 🔵 Post-merge audit (단계 2, rev-e2e-2-stages.md §3-2) 실행 → 통과 시 issue close
- 사후 회귀 검사 결과는 후속 plan 사이클 안에서 회고 spec 갱신 (`docs/features/emergency-hotfix-flow.md §6 사용 이력`)

### 2-2) 비-emergency 일반 hotfix (라벨 부적합)

- "fix 가 급한데 rev 대기가 길다" 단순 사유 — emergency 가 아님
- 정상 rev 사이클 통과 후 머지. emergency-hotfix 라벨 사용 금지.
- 라벨 부착 후 머지 → 다음 사이클 audit 에서 사용 빈도 카운트 → 월 임계치 초과 시 nmae 가 후속 회고 spec 추가 박제 (§8 Q2)

### 2-3) security 사고 hotfix

- 시크릿 유출 / XSS / 인증 우회 등 즉시 패치 필요
- 동일 라벨 (`type:emergency-hotfix`) 사용 + body 에 `security` 키워드 명시
- 머지 후 사후 issue body 에 별도 절차 (security disclosure / 영향 범위 평가) 박제 — 본 spec §6-2

## 3) 요구사항

### 기능 요구사항

#### 3-1) 라벨 정의

- [ ] `gh label create "type:emergency-hotfix" --color D73A4A --description "production down / security 즉시 패치 (rev-gate skip + 사후 rev 단계 2 의무)"`
- [ ] 색상 `D73A4A` — fix 계열 (시각적 일관성 + 위험 시그널)
- [ ] description 안에 사용 사유 + 사후 의무 명시 — gh UI 에서 hover 시 즉시 확인 가능

#### 3-2) 사용 정당 사유 (whitelist)

라벨 부착 정당화 가능한 시나리오만:

- production endpoint 5xx / down 즉시 복구
- security 사고 (시크릿 유출 / 인증 우회 / XSS / CSRF / sandbox escape) 즉시 패치
- data integrity 사고 (사용자 데이터 손상 / 잘못된 마이그레이션 즉시 revert)
- CI / build infra down 즉시 복구 (모든 작업 사이클 동시 정지)

**부적합 사유** (라벨 사용 금지):

- "rev 대기가 길어서" (단순 throughput 사유)
- "docs / spec 만 바꾸는데 굳이 rev 까지?" (no-op pass 가 1~2분 안에 끝남, 라벨 불요)
- "내가 직접 검토했으니 충분" (admin override 사유 — rev 사이클이 자율 객관 검토 보장)

#### 3-3) 사후 의무 (audit hook 자동 트리거)

- [ ] `rev-gate-audit.yml` (PR #1324 §3-3-2) 가 머지 trigger 시 자동 issue 생성 (`audit:emergency-hotfix-followup` 라벨)
- [ ] issue body 의무 박제 항목:
  - PR 링크 / mergedAt / mergedBy / merge commit SHA
  - emergency-hotfix 사유 (PR body 의 `## emergency-hotfix 사유` 섹션 grep)
  - 사후 rev 🔵 Post-merge audit (단계 2) 절차 참조 (`docs/features/rev-e2e-2-stages.md §3-2`)
  - 사후 회귀 검사 통과 기준 (production deploy 후 endpoint 정상 응답 확인)
- [ ] nmae 가 다음 사이클 안에 본 issue 처리 — rev sub-agent launch + 통과 시 issue close
- [ ] sub-agent 가 rev 단계 2 실패 시 → 즉시 revert PR 생성 + 사용자 DIGEST 알림

#### 3-4) PR body 필수 섹션

emergency-hotfix 라벨 부착 PR 은 body 에 다음 섹션 의무 명시:

```markdown
## emergency-hotfix 사유

- 사고 분류: production-down | security | data-integrity | ci-down
- 사고 시작 시각: YYYY-MM-DDTHH:MM:SSZ
- 사용자 impact 범위: <한 줄>
- root cause 추정: <한 줄>
- rollback 가능성: yes | no (이유)
- 사후 rev 단계 2 실행 사이클: 머지 후 첫 plan/rev 사이클
```

본 섹션 부재 시 audit hook 의 issue body 가 "사유 부재" 로 박제 → 사후 회고 spec 강제.

#### 3-5) 사용 빈도 추적 (감사 메트릭)

- [ ] audit hook 이 자동 생성한 issue 의 수 / `audit:emergency-hotfix-followup` 라벨 수가 월간 메트릭
- [ ] **§8 Q1**: 월 임계치 = (a) 1회 (경고만) / (b) 3회 (red dot) / (c) 무제한 (모든 사용을 issue 박제) — 미확정
- [ ] 임계치 초과 시 자동 후속 회고 spec 생성 (별 spec 후보, 본 spec scope 밖)

### 비기능 요구사항

- **관측성**: 모든 emergency-hotfix 사용은 audit issue + DIGEST + PR body 섹션 3 채널 박제 — 사후 회고 시 evidence 100% 보존
- **신뢰성**: rev-gate 우회 시 사용자 명시 확인 없이도 머지 가능 — branch protection (`enforce_admins=true`) 와 모순 X (라벨 부착이 사용자 명시 act 로 간주)
- **회복성**: 머지 후 즉시 revert 가능 (squash merge SHA 1개 revert)
- **남용 방지**: 사용 이력 메트릭 + 임계치 초과 시 자동 회고 spec 강제 (§3-5)
- **호환성**: 기존 rev / nmae / sub-agent 사이클 흐름 변경 X — 라벨 / workflow 추가만

## 4) 범위 / 비범위

### 포함

- `type:emergency-hotfix` 라벨 정의 / 색상 / description 박제
- 사용 정당 사유 / 부적합 사유 명문화 (§3-2)
- PR body 필수 섹션 (§3-4)
- 사후 audit issue 자동 생성 정책 (§3-3, PR #1324 와 sync)
- 사용 빈도 추적 메트릭 (§3-5)
- `02-agent-workflow.md §4 예외 정책` 본 spec cross-ref 보강
- CLAUDE.md §4 라벨 섹션 본 spec cross-ref 보강

### 제외 (Out of Scope)

- 사용 빈도 임계치 초과 시 자동 차단 (§3-5 Q1 — 차단 X, 회고 spec 강제만)
- security 사고 별 disclosure 절차 (별도 spec 후보)
- revert PR 자동 생성 (PR #1324 §8 Q4 와 sync)
- branch protection 변경 (PR #1322 §3-1 SoT)
- workflow yml 본문 (PR #1324 §6 PR 2 SoT)

## 5) 설계

### 5-1) 도메인 모델

- 기존: `ReviewedClaudeLabel`, `RevGateCheck` (06-domain-model.md §4 등재)
- 신설 후보 (본 spec accepted 시 등재):
  - `EmergencyHotfixLabel` — `type:emergency-hotfix` 라벨 자체
  - `EmergencyHotfixSeverity` — production-down | security | data-integrity | ci-down (PR body 섹션 enum)
  - `EmergencyHotfixFollowupIssue` — PR #1324 의 `audit:emergency-hotfix-followup` issue (cross-ref)

### 5-2) 라벨 생성 명령 (1회 실행, 사용자 또는 nmae)

```bash
gh label create "type:emergency-hotfix" \
  --color D73A4A \
  --description "production down / security 즉시 패치 (rev-gate skip + 사후 rev 단계 2 의무, docs/features/emergency-hotfix-flow.md SoT)"
```

### 5-3) 외부 연동

- gh CLI (라벨 생성, 1회만)
- `rev-gate.yml` (PR #1322 §3-2 — whitelist 라벨 추가, 별 PR)
- `rev-gate-audit.yml` (PR #1324 §3-3-2 — emergency-hotfix 분기, 별 PR)
- discord-reply.sh `--digest` (audit hook 의 DIGEST push, PR #1324 §5-3 와 sync)

### 5-4) 데이터 흐름 / 시퀀스

```text
[1] sub-agent / 사용자 PR 생성 + emergency-hotfix 라벨 부착 + body 사유 섹션 작성
[2] rev-gate.yml 가 whitelist 매칭 → workflow skip → check-run = green
[3] nmae / 사용자 즉시 머지 (rev 사이클 launch 대기 없음)
[4] rev-gate-audit.yml trigger → emergency-hotfix 분기
    ├ check-run rev-gate-audit = green (whitelist pass 사유 박제)
    ├ issue 자동 생성 (audit:emergency-hotfix-followup)
    └ DIGEST push (사용자 알림)
[5] nmae 가 issue queue 발견 → 다음 사이클 안에 rev 단계 2 launch
[6] rev 단계 2 통과 → issue close + PR 라벨 rev-post-merge-pass
[7] rev 단계 2 실패 → revert PR 생성 + 사용자 DIGEST + regression:dev 라벨
```

### 5-5) DB 마이그레이션

해당 없음 (infra-only).

### 5-6) 프론트엔드 화면

해당 없음.

## 6) 작업 분할 (예상 PR 리스트)

- [ ] PR 1 (본 spec 박제): docs only — 본 PR
- [ ] PR 2 (라벨 + 02-agent-workflow.md §4 cross-ref): 본 PR 안에서 같이 처리 — `02-agent-workflow.md §4` 에 본 spec cross-ref 추가
- [ ] PR 3 (라벨 생성 — `gh label create` 1회 실행): 사용자 직접 또는 nmae bootstrap script. 본 spec accepted 후 별 cycle
- [ ] PR 4 (`docs/ai-harness/02-agent-workflow.md §4` 예외 정책 세부 보강): 본 spec status=approved 후. 본 PR 에서는 cross-ref 한 줄만 추가
- [ ] PR 5 (CLAUDE.md §4 라벨 섹션 cross-ref): 본 spec status=shipped 후
- [ ] PR 6 (사용 이력 회고 spec 갱신): 라벨 첫 사용 후 1주일 안

### 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: ☐ 없음 (본 PR 한정)
- 본 spec 의 후속 PR (`rev-gate.yml` whitelist 갱신) 은 보호 영역 변경 — PR #1322 §3-2 SoT.

## 7) 테스트 전략

### 7-1) 라벨 정상 사용 시나리오

- dry-run PR 1건 생성 (실제 production 사고 아닌 trivial fix)
- `type:emergency-hotfix` 라벨 부착 → `rev-gate.yml` whitelist 통과 확인
- 머지 → `rev-gate-audit.yml` 가 issue + DIGEST 정상 생성 확인
- 사후 rev 단계 2 실행 → issue close 절차 verify

### 7-2) PR body 섹션 부재 시 audit issue body 박제 확인

- emergency-hotfix 라벨 부착 + body 사유 섹션 누락 PR → audit issue body 가 "사유 부재" 로 박제되는지 확인

### 7-3) 사용 빈도 카운트 검증

- 월 N건 audit issue 가 정확히 메트릭에 반영되는지 (gh issue list 카운트)

### 7-4) 라벨 부적합 사용 회수 시나리오 (사후 회고)

- 부적합 사유로 라벨 사용한 PR 발견 → rev sub-agent 가 회고 코멘트 + 다음 사이클 회고 spec 갱신

## 8) 오픈 질문

> 구현 전에 답이 나와야 하는 것들. 해소되면 §9 결정 로그로 이동.

| # | 질문 | 선택지 | 담당 / 기한 |
|---|---|---|---|
| Q1 | 월 사용 빈도 임계치 | (a) 1회 (초과 시 경고만) / (b) 3회 (초과 시 red dot + 회고 spec 강제) / (c) 무제한 (모든 사용을 audit issue 박제만) | @goohong / PR 2 launch 전. PR #1322 §8 Q2 + PR #1324 §8 Q2 와 sync |
| Q2 | security 사고 별도 disclosure 절차 분리 여부 | (a) 본 spec 안에 통합 (현 안) / (b) `docs/features/security-disclosure-flow.md` 별 spec / (c) `04-security-policy.md` 안에 통합 | @goohong / 별 spec 후보 |
| Q3 | nmae 가 audit issue 발견 후 자동 rev 단계 2 launch SLA | (a) 즉시 (다음 사이클 첫 launch) / (b) 24시간 / (c) best effort | @goohong / PR 1 머지 후 |
| Q4 | 사용 이력 자동 회고 트리거 (사용 후 N일) | (a) 7일 (현 안) / (b) 14일 / (c) 1개월 / (d) 분기 회고 안에 일괄 | @goohong / PR 6 launch 전 |
| Q5 | 라벨 부착 권한 — sub-agent vs nmae vs 사용자 | (a) 모두 허용 (현 안) / (b) nmae + 사용자만 / (c) 사용자만 (CODEOWNERS 게이트) | @goohong / 별 spec 후보 |

## 9) 결정 로그

> 연대기 순. "YYYY-MM-DD: 결정 / 이유 / 출처(PR 번호 등)"

- **2026-05-29**: 초안 작성 (status=draft). 트리거 — PR #1322 (`rev-gate-required-check-enforcement`) §3-2 + PR #1324 (`rev-gate-audit-workflow`) §3-3-2 의 운영 가이드 / 라벨 정책 / 사후 의무 통합. plan round 8 작업.
- **2026-05-29**: §3-2 사용 정당 사유 4종 + 부적합 사유 3종 명문화 — 라벨 남용 방지 1차 라인. §3-5 사용 빈도 메트릭 + 임계치 초과 시 회고 spec 강제 = 2차 라인.
- **2026-05-29**: §3-4 PR body 필수 섹션 — audit hook 이 grep 으로 사유 박제 가능. 섹션 부재 시 "사유 부재" 로 issue body 박제 → 사후 회고 강제.
- **2026-05-29**: §5-4 시퀀스 — rev-gate skip + audit issue + DIGEST + 사후 rev 단계 2 의무 chain. CLAUDE.md §4 "모든 PR 은 rev 사이클 통과 의무" 룰을 사후 단계 2 로 만족.
- **2026-05-29**: §6 본 PR 안에 `02-agent-workflow.md §4` cross-ref 추가까지 통합 — 검색 가능성 ↑ (예외 정책 → 본 spec 진입점).

## 10) 사용 이력 (감사 메트릭)

> 라벨 사용 시마다 nmae 또는 plan 사이클 안에서 본 섹션에 한 줄씩 추가. audit issue 번호 / mergedAt / 사유 분류.

(아직 사용 이력 없음 — 라벨 생성 후 첫 사용부터 박제)
