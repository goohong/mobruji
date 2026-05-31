---
feature: 시크릿 commit / push 방지 강제 메커니즘 (pre-commit hook + CI secret scanning)
slug: secret-scanning-hook
status: draft
owner: @goohong
scope: infra
related_issues: []
related_prs: []
last_reviewed: 2026-05-29
---

# 시크릿 commit / push 방지 강제 메커니즘 (pre-commit hook + CI secret scanning)

## 1) 개요 (What / Why)

`security-disclosure-flow.md §3-3` 가 security 사고 사후 rev 단계 2 추가 검증 4 항목 중 **4번째 — 회귀 방지 가드** 로 "pre-commit hook (시크릿 패턴 감지) 또는 secret scanning workflow 추가 cross-ref" 를 의무화했으나 단일 SoT spec 이 부재하다. 본 spec 은 그 강제 메커니즘 2 layer 를 박제한다.

핵심 원칙:
1. **사전 방지 우선** — 시크릿이 commit / push 단계에서 reject — 사후 회전 / history rewrite 부담 ↓
2. **2 layer 강제** — layer 1 (pre-commit hook, local) + layer 2 (CI workflow, server-side) — layer 1 우회 (`--no-verify`) 가능성 layer 2 가 server-side 강제
3. **prose 가 아니라 코드 강제** — `.git/hooks/pre-commit` (또는 `pre-commit` framework) + `.github/workflows/secret-scan.yml` 두 파일이 SoT
4. **evidence 박제** — 모든 감지 finding 은 `~/.mobruji/secret-scan-findings.jsonl` append + 자동 issue 신설 → 사후 회전 의무 추적 가능

본 spec 은 `security-disclosure-flow.md §3-3` 4 항목 (시크릿 회전 / git history 잔존 / 영향 범위 / 회귀 방지) 중 4번째의 강제 메커니즘 단일 SoT. 회전 / history rewrite 는 `security-disclosure-flow.md §3-1` 절차 안.

## 2) 사용자 시나리오

### 2-1) 로컬 commit 단계에서 시크릿 reject (layer 1)

- 개발 중 sub-agent / 사용자가 `.env.local` 또는 코드에 실제 시크릿 (AWS access key / GitHub PAT 등) 을 적어 `git commit` 시도
- pre-commit hook 가 시크릿 패턴 정규식 매칭 → commit reject + stderr 메시지 (감지 패턴 / 파일 / 라인)
- 사용자가 시크릿 제거 또는 placeholder 치환 → 재 commit 성공
- evidence 박제 X (local 단계 — 시크릿이 commit 안 됐으므로 추적 불필요)

### 2-2) `--no-verify` 우회 후 push → CI 차단 (layer 2)

- 사용자 / sub-agent 가 `git commit --no-verify` 로 layer 1 우회 → push
- GitHub Actions `secret-scan.yml` 가 PR push trigger 로 실행 → truffleHog / gitleaks 스캔 → 시크릿 1건 감지
- workflow fail → PR check 빨간색 → `rev-gate.yml` required check 차단 → 머지 불가
- `~/.mobruji/secret-scan-findings.jsonl` append (nmae 서버 cron 으로 GitHub Actions artifact pull → 박제) + 자동 issue 신설 (`audit:secret-scan-finding` 라벨)
- 사용자 / sub-agent 가 commit revert + 시크릿 회전 + history rewrite (`git filter-repo` / `BFG`) → 재 push → workflow green

### 2-3) git history 잔존 시크릿 사후 발견

- 과거 commit (예: 6개월 전) 에 시크릿이 commit 됐는데 발견 못 함 → 본 spec layer 2 가 매주 cron `secret-scan-history.yml` 실행 → 전체 history 스캔 → 발견 시 자동 issue 신설
- nmae 가 `EmergencyHotfixLabel` PR (시크릿 회전 + history rewrite) 즉시 launch → `security-disclosure-flow.md §3-1` 절차 시작
- evidence: `SecretScanFinding` entry 박제 (commit SHA / 파일 / 라인 / 감지 시각 / layer=2-history)

### 2-4) false positive 해소

- truffleHog / gitleaks 가 fixture / 테스트 데이터 / 예제 docs 의 fake 시크릿 (예: `aws_access_key_id = AKIAIOSFODNN7EXAMPLE`) 을 시크릿으로 분류
- 개발자가 `.gitleaksignore` 또는 `.trufflehog-ignore` 패턴 추가 PR — `rev-gate.yml` required check 통과 후 머지
- 이후 동일 패턴은 layer 1 / layer 2 모두 skip — false positive 해소

## 3) 요구사항

### 기능 요구사항

#### 3-1) Layer 1 — Pre-commit hook (로컬)

- [ ] `.git/hooks/pre-commit` 또는 `pre-commit` framework (`.pre-commit-config.yaml`) 으로 강제
- [ ] 감지 도구: `gitleaks protect --staged` (default) 또는 `trufflehog filesystem --only-verified` 중 1개
  - 채택 기준 = §8 Q1 (오픈 질문)
- [ ] 감지 패턴 set:
  - AWS access key id / secret access key
  - GitHub Personal Access Token (`ghp_` / `gho_` / `ghu_` / `ghs_` / `ghr_` prefix)
  - OAuth client secret (`client_secret` 명명 또는 base64 패턴)
  - DB password (`.env` / `application*.yml` / `docker-compose*.yml` 의 `password:` 라인)
  - 사내 API key (`MOBRUJI_*_KEY` / `DISCORD_TOKEN` / `OPENAI_API_KEY` 등 환경변수명 매칭)
- [ ] 감지 시 commit reject + stderr 출력 (패턴명 / 파일 / 라인) — file 박제 X (local 단계)
- [ ] `pre-commit` framework 사용 시 `.pre-commit-config.yaml` repo root commit + `README.md` 의 "Local setup" 섹션에 `pre-commit install` 안내

#### 3-2) Layer 2 — CI secret scanning workflow (server-side)

- [ ] `.github/workflows/secret-scan.yml` 신설
- [ ] trigger:
  - `pull_request: types: [opened, synchronize, reopened]` (PR diff 스캔)
  - `push: branches: [develop, main]` (머지 직후 head commit 스캔)
- [ ] 스캔 대상:
  - PR trigger = `git diff $BASE_SHA..$HEAD_SHA` (변경 라인만)
  - push trigger = head commit 단독
- [ ] 도구: layer 1 과 동일 (`gitleaks` 또는 `trufflehog`)
- [ ] `SecretScanFinding` ≥ 1 시 workflow fail → `rev-gate.yml` required check 차단
- [ ] artifact 박제: `secret-scan-findings.json` 업로드 — nmae 서버 cron 이 매시간 pull → `~/.mobruji/secret-scan-findings.jsonl` append
- [ ] 자동 issue 신설:
  - 라벨 = `audit:secret-scan-finding`
  - body = commit SHA / 파일 / 라인 / 감지 패턴명 / 회전 권고 cross-ref (`security-disclosure-flow.md §3-1` 절차)
  - assignee = `@goohong` (nmae 가 다음 사이클 안 처리)

#### 3-3) Layer 2-history — git history 주간 스캔

- [ ] `.github/workflows/secret-scan-history.yml` 신설 (cron `0 3 * * 1` UTC = 매주 월요일 03:00)
- [ ] 스캔 대상 = `git log --all` 전체 history
- [ ] 도구: `gitleaks detect --source . --log-opts="--all"` 또는 `trufflehog git file://. --since-commit <last-scan-sha>`
- [ ] `SecretScanFinding` ≥ 1 시 자동 issue 신설 (`audit:secret-scan-finding-history` 라벨 + 별 분기)
- [ ] 발견 시 nmae 가 `EmergencyHotfixLabel` PR 즉시 launch (시크릿 회전 + history rewrite + 사용자 통보)

#### 3-4) `SecretScanFinding` 박제 schema

- [ ] `~/.mobruji/secret-scan-findings.jsonl` append (nmae 서버, layer 2 / 2-history 만)
- [ ] entry schema:
  ```json
  {
    "finding_id": "uuid-v4",
    "detected_at_iso": "2026-05-29T10:00:00Z",
    "layer": "2|2-history",
    "pattern_name": "aws-access-key-id",
    "file_path": "backend/src/main/resources/application.yml",
    "line_number": 42,
    "commit_sha": "abc1234...",
    "pr_number": 1234,
    "workflow_run_id": 5678,
    "issue_number": 9012,
    "rotation_evidence_pr": null,
    "history_rewrite_evidence_pr": null,
    "status": "open|rotated|history_rewritten|false_positive"
  }
  ```
- [ ] `status` 전이:
  - `open` (초기) → `rotated` (시크릿 회전 PR 머지 시) → `history_rewritten` (history rewrite PR 머지 시)
  - 또는 `open` → `false_positive` (`.gitleaksignore` 추가 PR 머지 시)
- [ ] 모든 finding 의 `status` 가 `rotated` 또는 `history_rewritten` 또는 `false_positive` 도달까지 nmae 큐에 유지

#### 3-5) False positive 해소 채널

- [ ] `.gitleaksignore` 또는 `.trufflehog-ignore` (도구 선택에 따라) repo root commit
- [ ] 패턴 추가 PR 의 body 의무 섹션 = `## false positive 사유` (감지된 패턴명 / fixture 경로 / 실 시크릿 아닌 evidence)
- [ ] rev sub-agent 가 해당 PR 의 false positive 사유 평가 — fixture 가 아닌 실 시크릿 / 실 secret 의 prefix 만 가린 경우 거부

### 비기능 요구사항

- **관측성**: 모든 layer 2 / 2-history 감지가 (1) workflow fail (2) artifact 박제 (3) 자동 issue (4) `secret-scan-findings.jsonl` 4 채널에 동시 박제 — evidence 가 1 채널에만 있는 사고 0건
- **신뢰성**: workflow timeout 10분 / retry 1회 — GitHub Actions transient fail 으로 false negative 방지
- **회복성**: jsonl 손상 시 graceful skip (다음 cron 에서 재 fetch artifact) — daemon 죽지 않음
- **남용 방지**: layer 1 `--no-verify` 우회는 layer 2 가 강제, layer 2 `[skip ci]` 우회는 `rev-gate.yml` required check 가 차단
- **호환성**: 기존 workflow / hook 없는 repo 에 도입 — `pre-commit` framework 미설치 사용자는 `git commit` 정상 동작 (hook 미실행), CI layer 가 fallback 강제

## 4) 범위 / 비범위

### 포함

- §3-1 layer 1 pre-commit hook spec (`gitleaks protect --staged` 또는 동등)
- §3-2 layer 2 CI workflow spec (`.github/workflows/secret-scan.yml`)
- §3-3 layer 2-history 주간 cron spec (`.github/workflows/secret-scan-history.yml`)
- §3-4 `SecretScanFinding` jsonl schema
- §3-5 false positive 해소 채널 (`.gitleaksignore`)
- `06-domain-model.md §4` `SecretScanningHook` / `SecretScanFinding` 등재 (본 round 10 PR A 에서 사전 박제)
- `security-disclosure-flow.md §3-3` 4번째 항목 cross-ref 보강 (별 PR)

### 제외 (Out of Scope)

- 시크릿 회전 자동화 (vault rotation API 호출 등) — 본 spec scope X, `security-disclosure-flow.md §3-1` 절차 안
- git history rewrite 자동화 (`git filter-repo` / `BFG` 실행) — 본 spec scope X
- 시크릿 vault 도입 (AWS Secrets Manager / HashiCorp Vault) — 별 ADR 후보
- 자동 false positive 분류 (ML 모델) — 본 spec scope X
- pre-push hook (`pre-commit` 만 박제, pre-push 는 redundant)
- 도구 선택 (gitleaks vs trufflehog) — §8 Q1 오픈 질문, 본 spec 은 둘 다 호환 spec 박제

## 5) 설계

### 5-1) 도메인 모델

- 기존 (본 round 10 PR A 에서 등재): `SecretScanningHook` / `SecretScanFinding` (`06-domain-model.md §4`)
- 신설 없음 — 본 spec accepted 시 `SecretScanFinding` schema (`finding_id` / `layer` / `pattern_name` / `status` 등) 가 `06-domain-model.md §5` 엔티티 섹션 후보 (별 PR)

### 5-2) API 엔드포인트

해당 없음 (workflow + hook + jsonl 만).

### 5-3) 외부 연동

- `gitleaks` 또는 `trufflehog` (도구 선택 §8 Q1)
- `pre-commit` framework (선택 — §8 Q2)
- GitHub Actions (`pull_request` / `push` / `schedule` trigger)
- GitHub Issues API (자동 issue 신설)
- nmae 서버 cron (artifact pull → jsonl append)
- `rev-gate.yml` (required check 의존)
- `security-disclosure-flow.md §3-1` (시크릿 회전 절차 cross-ref)

### 5-4) 데이터 흐름 / 시퀀스

```text
[로컬 개발]
git commit
  └ [layer 1] pre-commit hook (gitleaks protect --staged)
      ├ 시크릿 매칭 0건 → commit 성공
      └ 시크릿 매칭 ≥ 1건 → commit reject + stderr 출력 (file 박제 X)

[PR push]
git push origin <branch>
  └ GitHub Actions `secret-scan.yml` trigger
      ├ [layer 2] gitleaks detect --source . --log-opts="$BASE_SHA..$HEAD_SHA"
      ├ 매칭 0건 → workflow green → rev-gate.yml required check 통과
      └ 매칭 ≥ 1건 → workflow fail
           ├ artifact 업로드 `secret-scan-findings.json`
           ├ 자동 issue 신설 (`audit:secret-scan-finding` 라벨)
           ├ rev-gate.yml required check 차단 → PR 머지 불가
           └ [nmae 서버 cron, 매시간] artifact pull → secret-scan-findings.jsonl append

[주간 cron]
매주 월요일 03:00 UTC
  └ `.github/workflows/secret-scan-history.yml`
      ├ gitleaks detect --source . --log-opts="--all"
      ├ 매칭 0건 → skip
      └ 매칭 ≥ 1건 → 자동 issue 신설 (`audit:secret-scan-finding-history` 라벨)
           └ nmae 다음 사이클 안 EmergencyHotfixLabel PR launch
               ├ 시크릿 회전 (vault / API revoke)
               ├ history rewrite (git filter-repo + force push)
               ├ security-disclosure-flow.md §3-1 절차 (사용자 통보 + Advisory)
               └ SecretScanFinding entry status=history_rewritten 갱신
```

### 5-5) DB 마이그레이션

해당 없음 (jsonl + GitHub issue 만).

### 5-6) 프론트엔드 화면

해당 없음.

## 6) 작업 분할 (예상 PR 리스트)

- [ ] PR 1 (본 spec 박제): docs only — 본 PR (round 10 PR B)
- [ ] PR 2 (`06-domain-model.md §4` 보강): 본 round 10 PR A 에서 사전 박제 (`SecretScanningHook` / `SecretScanFinding` 2건)
- [ ] PR 3 (`.github/workflows/secret-scan.yml` 신설 — layer 2): 본 spec status=approved 후 별 사이클 (보호 영역 변경 — `.github/workflows/**`)
- [ ] PR 4 (`.pre-commit-config.yaml` + `README.md` 안내 — layer 1): 본 spec status=approved 후 별 사이클 (보호 영역 변경 X, 단 `.git/hooks/` 정보성 분류)
- [ ] PR 5 (`.github/workflows/secret-scan-history.yml` 신설 — layer 2-history): 본 spec status=approved 후 별 사이클 (보호 영역 변경 — `.github/workflows/**`)
- [ ] PR 6 (`.gitleaksignore` 초기 fixture 패턴 등록): PR 3 머지 후 실 false positive 발견 시 별 사이클
- [ ] PR 7 (`security-disclosure-flow.md §3-3` 4번째 항목 cross-ref 보강): `security-disclosure-flow.md` PR #1328 머지 후 별 사이클
- [ ] PR 8 (nmae 서버 cron — artifact pull → jsonl append): PR 3 머지 후 별 사이클 (daemon 변경)
- [ ] PR 9 (`SecretScanFinding` schema 의 `06-domain-model.md §5` 엔티티 등재): 본 spec status=shipped 후 후보

### 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: ☐ 없음 (본 PR 한정 — docs only)
- 본 spec 후속 PR 3 / PR 5 는 `.github/workflows/**` 변경 — 보호 영역 변경 (정보성 분류, rev 가중도 추가)

## 7) 테스트 전략

### 7-1) Layer 1 — pre-commit hook 검증

- 실제 fake AWS access key (`AKIAIOSFODNN7EXAMPLE` 외 — 실 패턴 매칭 fixture) staging 후 `git commit` → reject 확인
- `--no-verify` 옵션 → commit 통과 → layer 2 가 잡는지 확인 (§7-2)
- `.gitleaksignore` 패턴 추가 후 → 같은 fixture 재 staging → commit 통과 (false positive 해소 확인)

### 7-2) Layer 2 — CI workflow 검증

- 의도적 시크릿 commit 한 PR (test branch) push → workflow fail 확인
- workflow artifact 다운로드 → `secret-scan-findings.json` schema 확인
- 자동 issue 신설 확인 (`audit:secret-scan-finding` 라벨 + body 박제 항목)
- `rev-gate.yml` required check 차단 확인 (PR 머지 버튼 비활성)

### 7-3) Layer 2-history — 주간 cron 검증

- workflow_dispatch manual trigger → 정상 git history 스캔 → 0건 확인
- 과거 history 에 fake 시크릿 inject test branch → manual trigger → 1건 감지 확인
- 자동 issue 신설 (`audit:secret-scan-finding-history` 라벨) 확인

### 7-4) jsonl append 멱등성

- 동일 finding 을 2회 cron 처리 → jsonl entry 1건만 추가 (`finding_id` 멱등 가드)
- jsonl 손상 시뮬레이션 → graceful skip + 다음 cron 정상 동작

### 7-5) False positive 해소 흐름

- fixture 패턴 (`example.com` / `test_*` prefix) → false positive PR 머지 → 이후 commit 통과 확인
- 실 시크릿의 prefix 만 가린 경우 → rev sub-agent 거부 → false positive PR 머지 안 됨 확인

## 8) 오픈 질문

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | 도구 선택 — gitleaks vs trufflehog | (a) gitleaks (단순 + 빠름 + pre-commit hook 표준) / (b) trufflehog (verified secret 우선, false positive ↓) / (c) 둘 다 (layer 1 = gitleaks, layer 2 = trufflehog) | @goohong / spec approved 전 |
| Q2 | pre-commit framework 도입 vs `.git/hooks/pre-commit` 직접 | (a) pre-commit framework (cross-language + 표준) / (b) `.git/hooks/pre-commit` 직접 (가볍지만 install 분산) | @goohong / spec approved 전 |
| Q3 | history 스캔 주기 — 주간 vs 일간 vs PR trigger | (a) 주간 (낮은 cost, 발견 지연 최대 7일) / (b) 일간 (cost 중간) / (c) PR 머지 trigger 마다 (cost 최대, 발견 즉시) | @goohong / spec approved 전 |
| Q4 | nmae 서버 cron artifact pull 간격 — 매시간 vs 매 PR fail 직후 webhook | (a) 매시간 cron (구현 단순) / (b) GitHub webhook `workflow_run` event (즉시 박제) | @goohong / spec approved 후 |
| Q5 | `SecretScanFinding.status` 전이 자동화 — PR body grep vs 수동 라벨 | (a) PR body `## secret rotation evidence` 섹션 자동 grep / (b) `rotated` / `history_rewritten` 라벨 수동 부착 | @goohong / spec approved 전 |
| Q6 | layer 1 미사용 사용자 (pre-commit framework install 안 함) → layer 2 만 강제 vs README warn | (a) layer 2 만 강제 (현 spec) / (b) `pre-commit install` 강제 CI check 추가 | @goohong / spec approved 후 |

## 9) 결정 로그

- 2026-05-29: 초안 작성 (status=draft). `security-disclosure-flow.md §3-3` 4번째 항목 (회귀 방지 가드) 단일 SoT 부재 사례 박제. plan round 10 trigger.
