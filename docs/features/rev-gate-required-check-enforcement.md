---
feature: rev-gate required check 강화 (admin override 차단)
slug: rev-gate-required-check-enforcement
status: draft
owner: @goohong
scope: infra
related_issues: []
related_prs: []
last_reviewed: 2026-05-29
---

# rev-gate required check 강화 (admin override 차단)

## 1) 개요 (What / Why)

`rev-gate.yml` workflow 는 `reviewed:claude` 라벨 + rev 🟡 Pre-merge review (단계 1) 통과 코멘트 부재 시 PR 머지를 차단하는 게이트로 설계되어 있다 (`docs/features/rev-e2e-2-stages.md §3-1`, [[feedback-rev-e2e-always]]). `develop` branch 의 `required_status_checks` 에 `rev-gate` 가 등록되어 있어 비-admin merge 는 정상 차단된다.

그러나 2026-05-29 PR #1318 사고로 다음 우회 경로가 노출되었다:

- `develop` branch protection `enforce_admins: false` — admin (repo owner) 이 status check 결과와 무관하게 즉시 머지 가능
- `main` branch 는 branch protection 자체 미설정 — release 시 `reviewed:claude` 라벨 미부착 PR 도 머지 가능
- post-merge 라벨 부착 → 사후 정렬은 가능하나 머지 시점 게이트 의도는 무력화

본 spec 은 **`reviewed:claude` 라벨 게이트가 admin override / branch protection 누락으로 우회되는 경로를 코드/protection 룰로 차단**하는 강화안을 박제한다. CLAUDE.md §4 "모든 PR 은 rev 사이클 통과 의무" 룰을 prose 가 아니라 GitHub branch protection + workflow + audit hook 으로 강제한다 ([[feedback-evidence-based-root-cause]] §17 — 메모리 재학습 대신 강제 메커니즘).

## 2) 사용자 시나리오

### 2-1) 정상 흐름 (admin 도 게이트 통과)

- helper / sub-agent 가 PR 생성 → rev 사이클 launch → `reviewed:claude` 라벨 + 단계 1 코멘트 부착
- `rev-gate` check green → admin 또는 nmae 자율 머지
- 게이트 통과 evidence 가 머지 시점에 100% 존재

### 2-2) 차단 흐름 (admin override 시도)

- helper / 사용자 직접 push 한 hotfix PR — rev 사이클 launch 전에 admin 머지 시도
- `rev-gate` check `failure` 또는 `pending` → branch protection `enforce_admins: true` 가 머지 버튼 비활성
- admin 가 "어차피 사후 라벨 부착하면 되니까" 머지 강행 시도 → GitHub 자체가 거절
- 진짜 hotfix emergency 시 — `type:release` whitelist 라벨 부여 또는 본 spec §3-2 emergency hotfix 절차 사용

### 2-3) 사후 audit (admin override 발생 시)

- 모든 머지 commit 에 대해 post-merge GitHub Action 이 라벨 부착 시점 vs 머지 시점 비교
- 머지 시점에 `reviewed:claude` 부재 → audit 실패 로그 (commit status `rev-gate-audit/failure`) 박제
- DIGEST 채널 자동 push (사용자 가시화)

## 3) 요구사항

### 기능 요구사항

#### 3-1) GitHub branch protection 강화 (gh API)

- [ ] `develop` branch protection 갱신 — `enforce_admins: true` 로 전환
  - 현 상태: `enforce_admins: false` (admin 우회 가능)
  - 변경 후: admin (repo owner) 도 `rev-gate` check green 없이는 머지 불가
- [ ] `main` branch protection 신설 (현재 미설정)
  - `required_status_checks`: `[rev-gate]` 등록
  - `enforce_admins: true`
  - `required_pull_request_reviews.required_approving_review_count: 1` (release PR 사용자 명시 확인 강제)
  - `allow_force_pushes: false`
  - `allow_deletions: false`
- [ ] **whitelist 정책 유지**: `type:release` 라벨 PR 은 rev-gate workflow 안에서 skip (현 `rev-gate.yml` line 53-59) — release PR 은 develop → main 머지 사용자 명시 확인이라 게이트 면제

#### 3-2) emergency hotfix 절차 (admin override 합법 경로)

- [ ] `type:emergency-hotfix` 라벨 신설 — production down / security 즉시 패치 한정
- [ ] `rev-gate.yml` whitelist 에 `type:emergency-hotfix` 추가 (release 와 동일 skip)
- [ ] emergency-hotfix 라벨 부착 시 자동 issue 생성 (post-merge action) — 사후 rev 단계 2 실행 트리거 + 사용자 DIGEST 알림 의무
- [ ] **남용 방지**: emergency-hotfix 라벨 사용 횟수가 월 1회 초과 시 audit 경고 (메트릭만 박제 — 차단까지는 안 함)

#### 3-3) post-merge audit hook (admin override 발생 시 사후 가시화)

- [ ] 신설 workflow `rev-gate-audit.yml` — `pull_request_target: types: [closed]` + `if: merged == true` 트리거
- [ ] 머지된 PR 의 다음 조건 검사:
  1. `reviewed:claude` 라벨 부착 시각이 mergedAt 보다 이른가
  2. 통과 코멘트 (`✅ rev e2e PR pass` / `📝 rev no-op pass`) 가 mergedAt 이전 존재하는가
  3. `type:release` / `type:emergency-hotfix` whitelist 부착되어 있는가
- [ ] 셋 다 NO → audit fail → 다음 action 분기:
  - DIGEST 채널 push (`tools/discord-daemon/discord-reply.sh --digest`)
  - GitHub issue 자동 생성 (라벨 `audit:rev-gate-bypass`)
  - 사후 rev 단계 2 의무 트리거 (`rev-stage2-post-merge.yml` 보완 호출)
- [ ] check-run 신설 `rev-gate-audit` — green/failure 두 상태만, merge commit 에 부착

#### 3-4) rev-gate workflow 자체 강건성 (현 워크플로 보완)

- [ ] `rev-gate.yml` 의 `pull_request: types` 에 `closed` 도 추가 — 머지 직전 1초 race window 차단 (라벨 부착 → 머지 → workflow 미실행 사고 방지)
- [ ] PR head commit 의 check-runs 가 0건인 상태에서 머지가 발생하는 경로 — branch protection `strict: true` (require branches to be up to date) 갱신 검토
  - trade-off: rebase 빈도 ↑ → 자율 사이클 throughput ↓. **§8 Q1 으로 오픈**

### 비기능 요구사항

- **관측성**: 모든 audit 실패는 DIGEST 채널 push + GitHub issue 두 채널 가시화 (사용자가 한 채널만 보고 있어도 누락 없음). [[feedback-helper-thread-usage]] 일관성
- **신뢰성**: branch protection 변경은 gh CLI script 1회 호출 — 사용자 직접 실행 필요 (gh API token scope `admin:repo` 필수). PR 안에서 자동 적용 불가
- **회복성**: 잘못된 protection 설정으로 머지 deadlock 발생 시 — gh API 로 즉시 rollback 가능 (`docs/features/rev-gate-required-check-enforcement.md §7` 롤백 절차 박제)
- **호환성**: 기존 `rev-gate.yml` / `rev-stage2-post-merge.yml` 본문 수정 없이 enhancement 만 — 회귀 risk ↓

## 4) 범위 / 비범위

### 포함

- `develop` `enforce_admins: true` 전환 (gh API)
- `main` branch protection 신설 (gh API)
- `type:emergency-hotfix` 라벨 신설 + `rev-gate.yml` whitelist 추가
- `rev-gate-audit.yml` 신설 (post-merge audit)
- 본 spec 본문 박제 + frontmatter

### 제외 (Out of Scope)

- rev sub-agent 의 SLA / 응답 시간 자동 측정 (별도 spec 후보 — §8 Q3)
- emergency-hotfix 라벨 사용 빈도 자동 차단 (월 1회 초과 시 차단까지 X — 메트릭만)
- `rev-stage2-post-merge.yml` 본문 변경 (현 워크플로 그대로 활용)
- branch protection UI 설정 (모든 변경 gh API SoT)
- 옛 PR 들에 대한 retro audit (본 spec 적용 시점부터)

## 5) 설계

### 5-1) 도메인 모델

본 spec 은 `docs/ai-harness/06-domain-model.md` 의 다음 컨셉을 참조 / 신설:

- 기존: `ReviewedClaudeLabel` (라벨), `RevGateCheck` (status check) — `§4 유비쿼터스 랭귀지` 등재됨
- 신설 후보: `AdminOverrideMerge` (admin enforce_admins false 우회 머지), `RevGateAuditBypass` (사후 audit fail event) — 본 spec accepted 시 §4 등재

### 5-2) gh API 호출 예시 (관리자 실행 의무)

```bash
# A. develop enforce_admins true 전환
gh api -X PUT /repos/goohong/mobruji/branches/develop/protection/enforce_admins

# B. main branch protection 신설
gh api -X PUT /repos/goohong/mobruji/branches/main/protection \
  -H "Accept: application/vnd.github+json" \
  -f required_status_checks[strict]=false \
  -F required_status_checks[contexts][]=rev-gate \
  -F enforce_admins=true \
  -F required_pull_request_reviews[required_approving_review_count]=1 \
  -F allow_force_pushes=false \
  -F allow_deletions=false \
  -f restrictions=null

# C. type:emergency-hotfix 라벨 생성
gh label create "type:emergency-hotfix" \
  --color D73A4A \
  --description "production down/security 즉시 패치 (rev-gate whitelist + 사후 rev 단계 2 의무)"
```

### 5-3) 외부 연동

- GitHub REST API (branch protection / labels)
- 기존 `tools/discord-daemon/discord-reply.sh` `--digest` 모드 (audit fail push)

### 5-4) 데이터 흐름 / 시퀀스

```text
[1] PR 생성 → rev sub-agent launch → 라벨 + 코멘트
[2] rev-gate.yml workflow → check-run = green
[3] merge button enable (enforce_admins=true 라도 통과)
[4] merge → rev-gate-audit.yml trigger
[5] audit: labelEvent.createdAt < mergedAt → green
[6] check-run `rev-gate-audit` green

(우회 시도 경로)
[3-bypass] admin merge 시도 → enforce_admins=true 라 GitHub 자체 거절
[3-emergency] type:emergency-hotfix 라벨 → rev-gate skip → 머지 가능
[5-emergency] audit: whitelist 매칭 → green + 사후 rev stage 2 의무 issue 생성
```

### 5-5) DB 마이그레이션

해당 없음 (infra-only).

### 5-6) 프론트엔드 화면

해당 없음.

## 6) 작업 분할 (예상 PR 리스트)

- [ ] PR 1 (본 spec 작성): docs only — 본 PR
- [ ] PR 2 (branch protection 갱신 — gh API 1회): 사용자 직접 실행 + 결과 commit message 박제. PR 자체는 docs (절차서 추가) — 별 cycle
- [ ] PR 3 (workflow 신설): `.github/workflows/rev-gate-audit.yml` + `rev-gate.yml` whitelist 갱신
- [ ] PR 4 (type:emergency-hotfix 라벨 생성 + 사용법 문서화): gh label create + `docs/ai-harness/02-agent-workflow.md` §4 보완
- [ ] PR 5 (post-merge audit hook 검증): rev cycle 안에서 일부러 우회 시도 → audit fail evidence 확보 + DIGEST push 확인

### 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: ☑ 있음 — 변경 파일과 사유:
  - `.github/workflows/rev-gate-audit.yml` (신설) — post-merge audit 강제 메커니즘
  - `.github/workflows/rev-gate.yml` (whitelist 추가) — emergency hotfix 우회 경로 추가
  - branch protection (gh API 호출, 파일 아님) — develop `enforce_admins` + main 신설
- rev 단계 1 audit 시 본 항목 가중도 ↑ (workflow + protection 동시 변경 → 회귀 시 머지 deadlock risk)

## 7) 테스트 전략

### 7-1) gh API 변경 검증

- 변경 직후: `gh api /repos/goohong/mobruji/branches/develop/protection | jq '.enforce_admins.enabled'` 가 `true` 인지 확인
- main: `gh api /repos/goohong/mobruji/branches/main/protection` 호출이 200 OK + `enforce_admins.enabled=true` 확인

### 7-2) admin override 차단 회귀 테스트

- 일부러 작은 docs PR 생성 → `reviewed:claude` 라벨 부착 안 한 상태에서 admin merge 시도 → GitHub UI 가 "Merging is blocked" 표시 여부 확인
- 확인 후 해당 PR 은 정상 절차 (rev 라벨 부착 후 머지) 로 복원

### 7-3) audit hook 회귀 테스트

- emergency-hotfix 라벨 PR 1건 생성 → 머지 → audit hook 이 사후 rev 단계 2 의무 issue 자동 생성 여부 확인
- 라벨 부재 + 머지 시도 → enforce_admins 가 차단했으므로 audit fail event 자체 발생 X 가 정상

### 7-4) 롤백 절차 검증 (deadlock 시)

```bash
# develop enforce_admins 다시 false 로 복원
gh api -X DELETE /repos/goohong/mobruji/branches/develop/protection/enforce_admins
```

본 명령으로 30초 안에 정상 머지 가능 상태로 복원되는지 1회 dry-run (실행 X — 본 spec 박제 시점엔 절차만 박제).

## 8) 오픈 질문

> 구현 전에 답이 나와야 하는 것들. 해소되면 §9 결정 로그로 이동.

| # | 질문 | 선택지 | 담당 / 기한 |
|---|---|---|---|
| Q1 | branch protection `strict: true` (require branches to be up to date) 활성화 여부 | (a) 활성화 (rebase 빈도 ↑, throughput ↓) / (b) 비활성 유지 (race window 0~1초 잔존) / (c) develop 만 false / main 만 true | @goohong / PR 3 머지 전 |
| Q2 | emergency-hotfix 라벨 사용 빈도 임계치 | (a) 월 1회 (경고만) / (b) 월 1회 (3회 누적 시 차단) / (c) 무제한 (audit log 만 남김) | @goohong / PR 4 머지 전 |
| Q3 | rev sub-agent 응답 SLA — 라벨 부착까지 의무 시간 | (a) 30분 (자율 사이클 평균) / (b) 1시간 / (c) SLA 박제 X — best effort | rev sub-agent / 별도 spec 후보 |
| Q4 | post-merge audit 실패 시 자동 revert 여부 | (a) issue + DIGEST 만 (현 안) / (b) auto revert PR 생성 / (c) revert 후보 라벨만 부여 | @goohong / PR 3 머지 전 |
| Q5 | main branch `required_approving_review_count` — 0 vs 1 vs 2 | (a) 0 (rev-gate workflow 단독 의존) / (b) 1 (사용자 직접 1회 approve 강제) / (c) 2 (Claude approve + 사용자 approve) | @goohong / PR 2 (protection 갱신) 전 |

## 9) 결정 로그

> 연대기 순. "YYYY-MM-DD: 결정 / 이유 / 출처(PR 번호 등)"

- **2026-05-29**: 초안 작성 (status=draft). 트리거 — PR #1318 admin override merge 사고 (rev round 31 발견). evidence: branch protection `enforce_admins: false` 확인 (gh api) + PR #1318 timeline (mergedAt 08:53:49Z, `reviewed:claude` labeled 08:56:50Z) + head commit check-runs 0건 = rev-gate workflow 완료 전 머지. plan round 7 작업.
- **2026-05-29**: §3-1 `enforce_admins: true` 채택 사유 — CLAUDE.md §4 "모든 PR 은 rev 사이클 통과 의무" + [[feedback-evidence-based-root-cause]] §17 강제 메커니즘 우선순위 (system prompt < hook < wrapper < cron < 메모리) 중 branch protection = "hook" 등급 차단.
- **2026-05-29**: §3-2 emergency-hotfix 라벨 신설 — admin override 의 합법 경로 분리 (사고 vs 의도 분리). 모든 사용 사례에 사후 rev 단계 2 의무 issue → emergency hotfix 도 결국 audit log 박제.
- **2026-05-29**: §3-3 post-merge audit workflow — `rev-gate.yml` 만으로는 race window (workflow 미실행 머지) 차단 불가. `pull_request_target: closed` trigger 로 사후 평가만 보장.
- **2026-05-29**: §4 제외 — rev SLA 자동 측정은 별 spec 후보 (본 spec 은 차단 강화에 집중, 측정/SLA 는 별도). emergency-hotfix 사용 빈도 자동 차단도 1차 박제 X (남용 발견 시 후속 spec).
