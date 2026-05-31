---
feature: security 사고 비공개 disclosure 절차 및 사후 PR rev 우선순위
slug: security-disclosure-flow
status: draft
owner: @goohong
scope: infra
related_issues: []
related_prs: []
last_reviewed: 2026-05-29
---

# security 사고 비공개 disclosure 절차 및 사후 PR rev 우선순위

## 1) 개요 (What / Why)

`emergency-hotfix-flow.md §2-3` 가 security 사고 시 `type:emergency-hotfix` 라벨 + body `security` 키워드 명시 + 머지 후 별도 절차 (security disclosure / 영향 범위 평가) 박제를 cross-ref 후보로 남겼다 (§6-2 placeholder). 본 spec 은 그 절차를 단일 SoT 로 박제한다.

핵심 원칙:
1. **비공개 우선** — 미패치 취약점은 public PR / public issue 에 노출 금지. GitHub Security Advisory (private repo report) 또는 사내 채널만 사용.
2. **사후 박제** — 패치 머지 후 (또는 사용자 명시 disclosure 시점에) public CHANGELOG / advisory publish 의무.
3. **사용자 통보** — 영향 받은 사용자 (음역대 / Like / Bookmark 데이터 노출 가능성) 가 1명 이상이면 사용자 channel DIGEST + Discord 본 채널 양쪽 명시 통보 의무.
4. **rev 우선순위 강제** — security 분류 PR (`EmergencyHotfixSeverity = security` + body 키워드) 은 rev 큐 head 강제 + rev 단계 1 SLA 30분 (별도 spec `rev-sla.md` cross-ref).

본 spec 은 **차단이 아니라 절차 박제** — security 사고 발생 시 sub-agent / nmae / 사용자가 한 곳에서 절차 순서 / 채널 / 의무 항목을 확인 가능하게 한다 ([[feedback-evidence-based-root-cause]] §17 — 강제 메커니즘 우선).

## 2) 사용자 시나리오

### 2-1) production 시크릿 유출 사고 (외부 노출 X)

- 개발 중 sub-agent / 사용자가 시크릿 (DB 비밀번호 / API key / OAuth client secret) 이 git history / `.env` / public log 에 노출됐음을 발견
- 본 spec §3-1 절차 시작:
  1. nmae 가 즉시 `type:emergency-hotfix` 라벨 + body `security` 키워드 PR 생성 (시크릿 무효화 + 회전)
  2. GitHub Security Advisory (private repo report) draft 작성 — 공개는 패치 머지 후
  3. `rev-gate.yml` whitelist 매칭 → 즉시 머지 가능
  4. 머지 후 `RevGateAuditCheck` whitelist pass 분기 → `EmergencyHotfixFollowupIssue` 자동 신설
  5. nmae 가 다음 사이클 안에 rev 단계 2 launch — 본 spec §3-3 추가 검증 (시크릿 회전 완료 확인 / git history rewrite 또는 시크릿 revoke API 호출 evidence)
  6. 통과 시 Security Advisory publish + CHANGELOG `## Security` 섹션 추가 + 사용자 통보 (외부 노출 0 분류 시 통보 면제)

### 2-2) production 사용자 데이터 노출 사고

- public endpoint 가 사용자 음역대 / Like / Bookmark / sessionId 를 권한 없이 응답하는 회귀 발견
- 본 spec §3-1 절차 시작 + §3-4 사용자 통보 채널 의무 추가 (외부 노출 0 분류 X)
- 패치 머지 + Security Advisory publish + 사용자 통보 (Discord 본 채널 + DIGEST + GitHub Release notes `## Security` 섹션)
- 영향 범위 평가 (얼마나 많은 sessionId 의 데이터가 노출됐는가) 를 evidence 로 issue body 박제

### 2-3) 인증 우회 / XSS / CSRF / sandbox escape

- 자율 사이클 sub-agent 가 발견 (rev / be / fe 모두 가능) → 즉시 보고 + 별도 PR (public 가능 — 시크릿 미포함 시) 또는 private fork 패치 (시크릿 포함 시)
- 본 spec §3-1 절차 + §3-2 disclosure 분류 매트릭스 적용

## 3) 요구사항

### 기능 요구사항

#### 3-1) 절차 (모든 security 사고 공통)

- [ ] **발견 즉시 비공개 채널 보고** — 사용자 DM (Discord) 또는 사내 1:1 채널만. public PR / public issue / public Discord 채널 사용 금지
- [ ] **GitHub Security Advisory draft 생성** — `gh api repos/goohong/mobruji/security-advisories` 또는 GitHub UI (Security tab) — public 가시화는 패치 머지 후
- [ ] **`type:emergency-hotfix` 라벨 + body `security` 키워드 PR 생성** — `emergency-hotfix-flow.md §3-4` body 섹션 필수 (사고 분류 `security`)
- [ ] **`rev-gate.yml` whitelist 매칭** — 즉시 머지 가능 (rev 사이클 대기 X)
- [ ] **머지 후 `RevGateAuditCheck` whitelist pass 분기** — `EmergencyHotfixFollowupIssue` 자동 신설 (`audit:emergency-hotfix-followup` + `security` 라벨 추가)
- [ ] **rev 단계 2 launch (다음 사이클 안)** — 본 spec §3-3 추가 검증 항목 포함
- [ ] **통과 시 publish** — Security Advisory publish + CHANGELOG `## Security` 섹션 + (영향 있을 시) 사용자 통보

#### 3-2) disclosure 분류 매트릭스

| 분류 | 정의 | 예시 | 외부 노출 위험 | 사용자 통보 의무 | CVE 신청 |
|---|---|---|---|---|---|
| 🔴 critical-public | production 가시 endpoint 가 사용자 데이터 / 시크릿 노출 중 | 인증 우회로 다른 sessionId 데이터 응답 | 즉시 가시 | 의무 | 검토 |
| 🟠 high-internal | production 시크릿이 git history / log 에 노출 (외부 미가시) | DB 비밀번호 commit, API key log 출력 | 잠재 | 사용자 통보 면제 (단 시크릿 회전 완료 evidence 박제) | 면제 |
| 🟡 medium-poc | 비-production (dev / local) 환경 시크릿 노출 또는 PoC 단계 취약점 | local docker-compose `.env` git commit, dev fixture XSS | 낮음 | 면제 | 면제 |
| 🟢 low-theoretical | 이론적 취약점 / 미악용 가능 | DependencyChecker false positive, 사용 안 하는 라이브러리 CVE | 0 | 면제 | 면제 |

본 분류는 PR body `## emergency-hotfix 사유` 섹션의 `사용자 impact 범위` 한 줄에 추가 — `🔴/🟠/🟡/🟢 <분류명>` prefix 강제.

#### 3-3) 사후 rev 🔵 Post-merge audit (단계 2) 추가 검증 항목 (security 한정)

`rev-e2e-2-stages.md §3-2` 의 정규 🔵 Post-merge audit (단계 2) 검증에 더해 다음 evidence 박제 의무 — rev sub-agent 가 통과 코멘트 (`rev단계2: 🟢 ...`) 에 포함:

- [ ] 시크릿 회전 완료 evidence — `gh secret list` / vault rotation log / API key revoke timestamp
- [ ] git history 잔존 검사 — `git log --all -S "<유출 시크릿 prefix>"` 결과 0건 (또는 history rewrite + force push evidence)
- [ ] 영향 범위 평가 — 영향 받은 sessionId 수 / 노출된 컬럼 수 / 외부 endpoint hit count
- [ ] 회귀 방지 가드 — pre-commit hook (시크릿 패턴 감지) 또는 secret scanning workflow 추가 cross-ref

실패 항목 1개 이상 → rev 단계 2 fail → 즉시 revert PR + `regression:dev` 라벨 + 사용자 DIGEST.

#### 3-4) 사용자 통보 채널 (🔴 critical-public 분류 한정)

영향 받은 사용자가 1명 이상 (`EmergencyHotfixFollowupIssue` body 의 `영향 받은 sessionId 수` 박제 값 ≥ 1) 인 경우 다음 채널 동시 통보:

- [ ] **Discord 본 채널** (`#모부르지`) — 사용자가 즉시 가시 가능한 채널
- [ ] **DIGEST 채널** — 사용자가 매일 보는 status 채널 (`discord-reply.sh --digest`)
- [ ] **GitHub Release notes** — 다음 release PR body 의 `## Security` 섹션 박제 (release 사이클이 본 패치를 포함하는 경우)
- [ ] **Security Advisory publish** — GitHub UI 의 Security tab 에서 가시화

통보 본문 박제 항목:
- 사고 발생 시각 (T0) / 패치 머지 시각 / 사용자 통보 시각
- 영향 받은 데이터 종류 (음역대 / Like / Bookmark / sessionId / 시크릿)
- 영향 받은 사용자 수 (sessionId 단위)
- 사용자가 해야 할 action (예: sessionId rotation 권고 — `POST /api/v1/sessions/rotate`)

#### 3-5) `.github/SECURITY.md` 신설

- [ ] repo root 의 `.github/SECURITY.md` (또는 `SECURITY.md`) 박제
- [ ] 외부 사용자 (오픈소스 contributor) 가 취약점 발견 시 보고 채널 안내
- [ ] 보고 채널 = GitHub Security Advisory (private report) — public issue / PR 금지 명시
- [ ] 응답 SLA = 본 spec §3-1 절차 시작까지 7일 안 (initial response)
- [ ] supported versions = 현재 default branch (`develop`) + 최근 release (`main`) — 2 channel 만

### 비기능 요구사항

- **관측성**: 모든 security 사고는 (1) Security Advisory + (2) `EmergencyHotfixFollowupIssue` + (3) (🔴 분류 시) DIGEST + Discord 본 채널 + Release notes 동시 박제 — evidence 가 한 채널에만 있는 사고 0건
- **신뢰성**: rev 단계 2 추가 검증 4 항목 (§3-3) 미통과 PR 은 자동 revert — security 회귀가 production 으로 흐르지 않음
- **회복성**: 모든 security 패치 PR 은 squash merge SHA 1개 revert 가능 (`emergency-hotfix-flow.md §3-2` 와 sync — rollback 가능성 body 명시 의무)
- **남용 방지**: 사용 빈도 메트릭은 `emergency-hotfix-flow.md §3-5` 가 흡수 — 본 spec 은 절차만 박제
- **호환성**: `emergency-hotfix-flow.md` / `rev-gate-required-check-enforcement.md` / `rev-e2e-2-stages.md` 본문 수정 없이 cross-ref enhancement 만

## 4) 범위 / 비범위

### 포함

- security 사고 발생 시 절차 단계 박제 (§3-1)
- disclosure 분류 매트릭스 (§3-2) — 사용자 통보 / CVE 신청 의무 결정 기준
- rev 단계 2 추가 검증 4 항목 (§3-3)
- 사용자 통보 채널 (§3-4) — 🔴 critical-public 분류 한정
- `.github/SECURITY.md` 신설 가이드 (§3-5)
- `emergency-hotfix-flow.md §2-3` cross-ref 보강

### 제외 (Out of Scope)

- 시크릿 패턴 감지 pre-commit hook 본문 (별 spec 후보 — `secret-scanning-hook.md` 예정)
- secret scanning workflow yml 본문 (별 spec / 별 PR)
- CVE 신청 절차 본문 (CVE Numbering Authority 등록 후 별 spec)
- security 사고 사후 회고 spec (회고 자체는 본 spec 의 audit issue → 별 plan 사이클 trigger)
- `rev-sla.md` (단계 1 응답 SLA — 별 spec, 본 spec 은 cross-ref 만)
- 코드 구현 (pre-commit hook / scanning workflow / `.github/SECURITY.md` 본문 — 별 PR)

## 5) 설계

### 5-1) 도메인 모델

- 기존: `EmergencyHotfixLabel`, `EmergencyHotfixSeverity`, `EmergencyHotfixFollowupIssue`, `RevGateAuditCheck` (`06-domain-model.md §4` 등재)
- 신설 후보 (본 spec accepted 시 등재):
  - `SecurityAdvisory` — GitHub Security Advisory (private report → public publish 라이프사이클)
  - `SecurityDisclosureCategory` — `critical-public` / `high-internal` / `medium-poc` / `low-theoretical` 4 enum (§3-2 매트릭스)
  - `SecurityUserNotification` — 사용자 통보 evidence (채널 / 시각 / 영향 sessionId 수)

본 spec status=approved 전환 시 06-domain-model.md §4 보강 PR 별도 사이클 후보.

### 5-2) 외부 연동

- GitHub Security Advisory API (`gh api repos/.../security-advisories`)
- GitHub Release notes (`gh release create --notes-file`)
- `discord-reply.sh --digest` (DIGEST 통보)
- `discord-reply.sh --reply` (Discord 본 채널 통보)
- `emergency-hotfix-flow.md §3-1` 라벨 / body 섹션
- `rev-gate-required-check-enforcement.md §3-3` audit check-run / `EmergencyHotfixFollowupIssue`

### 5-3) 데이터 흐름 / 시퀀스

```text
[T0] sub-agent / 사용자 security 사고 발견
[T0+] 비공개 채널 보고 (사용자 DM / 사내 1:1) — public PR/issue 금지
[T0+1m] GitHub Security Advisory draft 생성 (private)
[T0+5m] type:emergency-hotfix 라벨 + body security 키워드 PR 생성
[T0+5m] rev-gate.yml whitelist 매칭 → workflow skip → mergeable
[T0+10m] nmae / 사용자 즉시 머지
[T0+10m+1s] RevGateAuditCheck whitelist pass 분기
    ├ EmergencyHotfixFollowupIssue 자동 신설 (security 라벨 추가)
    └ DIGEST push (rev 단계 2 launch 큐 진입)
[T0+다음 사이클] nmae 가 rev 단계 2 launch — §3-3 추가 검증 4 항목 박제
    ├ 통과 → Security Advisory publish + CHANGELOG ## Security + (🔴 분류 시) §3-4 사용자 통보
    └ 실패 → 즉시 revert PR + regression:dev + 사용자 DIGEST
[T0+publish] (🔴 critical-public) §3-4 사용자 통보 채널 4개 동시 박제
```

### 5-4) DB 마이그레이션

해당 없음 (infra-only).

### 5-5) 프론트엔드 화면

해당 없음.

## 6) 작업 분할 (예상 PR 리스트)

- [ ] PR 1 (본 spec 박제): docs only — 본 PR
- [ ] PR 2 (`emergency-hotfix-flow.md §2-3` + §6-2 cross-ref 보강): 본 spec status=approved 후 별 사이클
- [ ] PR 3 (`.github/SECURITY.md` 신설): 본 spec status=approved 후 별 사이클 — **보호 영역 변경 (`.github/`)**
- [ ] PR 4 (06-domain-model.md §4 보강 — `SecurityAdvisory` / `SecurityDisclosureCategory` / `SecurityUserNotification` 등재): 본 spec status=approved 후
- [ ] PR 5 (`rev-e2e-2-stages.md §3-2` cross-ref 보강 — security 분류 시 §3-3 추가 검증 항목 의무): 본 spec status=shipped 후
- [ ] PR 6 (`secret-scanning-hook.md` 별 spec 신설): 본 spec 과 독립

### 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: ☐ 없음 (본 PR 한정 — docs only)
- 본 spec 후속 PR 3 (`.github/SECURITY.md` 신설) 은 보호 영역 변경 (`.github/**`) — rev 사이클 가중도 추가 신중도 권고

## 7) 테스트 전략

### 7-1) 절차 시뮬레이션 (dry-run)

- 가상 security 사고 시나리오 (`.env` 누출) 로 §3-1 절차 7 단계를 dry-run
- 각 단계 evidence (Security Advisory draft URL / PR URL / 라벨 / body 섹션 / audit issue URL / rev 단계 2 코멘트 / publish URL) 박제 — drift 검사

### 7-2) disclosure 분류 매트릭스 검증

- §3-2 매트릭스의 4 분류 각각에 대해 1 예시 PR body 작성
- 사용자 통보 의무 / CVE 신청 의무 분기 확인

### 7-3) rev 단계 2 추가 검증 항목 (§3-3) 통합 테스트

- 시크릿 회전 evidence 가 4 항목 모두 박제됐는지 검사
- 1 항목 누락 → rev 단계 2 fail 분기 동작 확인

### 7-4) 사용자 통보 채널 (§3-4) 동시 박제 검증

- 🔴 critical-public 사고 시뮬레이션 → 4 채널 (Discord 본 채널 / DIGEST / Release notes / Security Advisory) 모두 박제 확인
- 1 채널 누락 → audit issue body 에 누락 박제 + 재통보 trigger

## 8) 오픈 질문

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | Security Advisory publish 시점 = (a) rev 단계 2 통과 즉시 / (b) release PR 머지 시 / (c) 사용자 명시 확인 후 | (a) / (b) / (c) | @goohong / spec accepted 전 |
| Q2 | 🟠 high-internal 분류 사용자 통보 면제 — 시크릿 회전 evidence 가 외부 hit count 0 보장 못 할 경우 분류 상향 강제 여부 | yes (자동 🔴 승급) / no (수동 결정) | @goohong / spec approved 전 |
| Q3 | `.github/SECURITY.md` 의 응답 SLA initial response 7일 — 자율 사이클 nmae 가 24시간 안에 대응 가능한지 ([[feedback-keep-4-cycles-active]]) — SLA 단축 가능 여부 | (a) 7일 유지 / (b) 24h 단축 / (c) 48h 절충 | @goohong / `.github/SECURITY.md` 신설 PR 전 |
| Q4 | CVE 신청 의무 (🔴 critical-public 한정) — 신청 채널 / 절차 별 spec 신설 vs 본 spec 흡수 | (a) 본 spec §3-6 신설 / (b) 별 spec | @goohong / 첫 🔴 사고 발생 후 |
| Q5 | rev 단계 2 추가 검증 4 항목 (§3-3) 의 git history 잔존 검사 — `git log --all -S` 가 false positive 가능. alternative tool (trufflehog / gitleaks) 도입 여부 | (a) `git log -S` 유지 / (b) gitleaks 도입 (별 PR) | @goohong / 첫 🟠 이상 사고 발생 후 |

## 9) 결정 로그

- 2026-05-29: 초안 작성 (status=draft). `emergency-hotfix-flow.md §2-3` + §6-2 cross-ref 후보를 단일 SoT 로 박제 (plan round 9 trigger).
