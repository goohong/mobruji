---
feature: Branch + Issue Cleanup Policy (expanded SoT)
slug: branch-and-issue-cleanup-policy
status: approved
owner: @mobruji-maestro
scope: infra
related_issues: [1127]
related_prs: [1126, 1128]
last_reviewed: 2026-05-26
---

# Branch + Issue Cleanup Policy (expanded SoT)

> **본 문서가 expanded scope SoT** 입니다.
> 기존 partial spec `docs/features/branch-cleanup-policy.md` (PR #1126, HOLD/conflict) 가 branch-only scope 였다면, 본 문서는 (a) **remote branch** + (b) **local branch** + (c) **closed GitHub issue** + (d) **milestone** 까지 통합 SoT 로 확장합니다.
> 향후 둘 중 하나가 단일화될 때까지는 본 문서가 우선합니다.

## 1) 목적 (What / Why)

사용자 2026-05-26 16:04 KST directive — remote branch 451 / closed issue 416 / open issue 142 적체 상태 가시화. 적체 누적 시 다음 위험.

- **개발자 cognitive load 증가**: `git branch -r` / `gh issue list` 결과가 100+ 행 — 의미 있는 신호 식별 불가.
- **git fetch / clone latency 증가**: 451 remote ref → fetch 시간 비례.
- **stale ref 기반 사고**: 머지된 branch 를 base 로 한 새 PR 분기 → 충돌 / 회귀.
- **issue 검색 노이즈**: closed 416 건이 검색 결과를 dominate — 활성 142 건이 가려짐.

목표:
1. 일회성 backlog drain (451 → 100 미만, closed issue 416 → label 부여 + milestone close).
2. 신규 사이클 마다 stale 누적 방지 (지속 가능 운영) — cron / release tag 직전 schedule.

## 2) 사용자 시나리오

- **시나리오 A (개발자)**: 신규 사이클 진입 시 `git branch -r` 결과가 100 행 미만으로 정돈된 상태 — 활성 PR / 활성 사이클 branch 만 보임. stale 식별 시간 0.
- **시나리오 B (nmae 오케스트레이션)**: 사이클 완료 후 squash merge → 30 일 경과 → 자동 sweep 후보 등록 → dry-run report PR 생성 → 사용자 review → `--apply --yes` 1회 → cycle 종료.
- **시나리오 C (rev 코드 리뷰)**: closed-not-merged branch / orphan issue 식별 — referent 보존 여부 결정 후 cleanup.

## 3) 요구사항

### 기능 요구사항

- [ ] **squash merge 환경 호환 식별** — `git branch -r --merged` 가 0건 반환하는 squash merge 환경에서도 정확히 머지 branch 식별 (gh pr list state=merged 기반).
- [ ] **whitelist 보호** — 영구 (`main` / `develop` / `release/*` / `hotfix/*`) + 일시 (open PR head) + 활동 (최근 7 일 commit) + 명시 (`.mobruji-protected-branches.txt` 와일드카드) 4 단계 보호.
- [ ] **임계 분기** — 자동 삭제 가능 (--apply --yes, squash-merge 후 30 일+) vs 수동 confirm (그 외) 명확 구분.
- [ ] **dry-run default** — sweep 스크립트는 `--apply` 명시 없으면 절대 삭제 수행 안 함. report markdown 출력만.
- [ ] **closed issue stale 라벨** — 30 일+ closed 이슈 `stale-cleanup-YYYY-QN` 라벨 자동 부착. cross-PR link / ADR referent 보존.
- [ ] **milestone 자동 close 옵션** — 모든 이슈 closed 인 milestone 은 운영 결정 시 자동 close.

### 비기능 요구사항

- **idempotent**: 같은 임계로 N 회 호출해도 결과 동일.
- **audit log**: 모든 삭제 결정은 `tools/branch-cleanup/sweep.log` 또는 PR/issue 코멘트로 남김.
- **rollback 가능**: 삭제 후 7 일 이내 `git push origin <branch>` 또는 GitHub Restore branch 윈도우 활용.
- **rate-limit safe**: gh api / git push 호출 timeout 30s + 3 회 재시도.

## 4) 범위 / 비범위

### 포함

- remote branch (`refs/remotes/origin/*`) 식별 + 삭제 정책.
- local branch (`refs/heads/*`) 식별 + 삭제 정책.
- `.claude/worktrees/agent-*` worktree dir 정리 (기존 PR #1126 spec scope 인계).
- closed GitHub issue stale 라벨링 + milestone 자동 close.

### 제외 (Out of Scope)

- **tag 정리** — 릴리즈 tag 는 영구 보존. 별도 spec 필요 시 신설.
- **fork repo branch 정리** — origin 만 대상.
- **draft PR 자동 close** — 본 spec 은 branch / issue 정리에 한정.
- **closed PR 자체 정리** — GitHub 은 PR 자체를 영구 보존 — head branch 만 정리 대상.
- **CI workflow run 보관 기간** — 별도 GitHub 설정으로 관리.

## 5) 설계

### 5-1) 식별 (gh pr list 기반 — squash merge 호환)

`git branch -r --merged` 가 squash merge 환경에서 0 건 반환하는 한계를 회피.

```bash
# 머지된 branch (squash merge 포함)
gh pr list --state merged --limit 1000 --json number,headRefName,mergedAt

# closed-not-merged branch (인계 후보)
gh pr list --state closed --limit 1000 --json number,headRefName,closedAt,merged \
  | jq '.[] | select(.merged == false)'

# 활동 timestamp (보호 룰 판정용)
git for-each-ref refs/remotes/origin --sort=-committerdate \
  --format='%(committerdate:iso8601) %(refname:short)'
```

식별 단계:
1. remote branch 전체 enumerate (`git ls-remote --heads origin`).
2. 각 branch 마다 (a) merged PR head 여부 (b) open PR head 여부 (c) 최근 commit 일자 (d) whitelist 매칭 4 가지 dimension 수집.
3. (b) OR (c) OR (d) 인 branch 는 cleanup 대상 외 (보호).
4. (a) merged + (c) 30 일+ 경과 + whitelist 미매칭 → 자동 삭제 후보.
5. 그 외 cleanup 후보 → 수동 confirm 후보.

### 5-2) 보호 룰 (whitelist — 절대 삭제 금지)

| 분류 | 매칭 | 갱신 주기 |
|---|---|---|
| 영구 | `main` / `develop` / `release/*` / `hotfix/*` | 변경 없음 |
| 일시 | OPEN PR head branch (`gh pr list --state open --json headRefName`) | sweep 실행 시점 |
| 활동 | 최근 7 일 이내 committerdate | sweep 실행 시점 |
| 명시 | `.mobruji-protected-branches.txt` 와일드카드 한 줄당 한 패턴 (`fnmatch` 또는 `git check-ref-format` 호환) | 수동 |

`.mobruji-protected-branches.txt` 예시 (옵션):
```
release/*
hotfix/*
docs/longterm-*
experiment/*
```

### 5-3) 삭제 임계 (자동/수동 분기)

| 카테고리 | 조건 | 처리 |
|---|---|---|
| 자동 삭제 가능 (`--apply --yes`) | squash-merge 후 **30 일+** 경과 + whitelist 미매칭 | `git push origin --delete <branch>` 1회 |
| 수동 confirm 필요 | merge 후 30 일 이내 OR closed-not-merged | dry-run report 등록 → 사용자 결정 → 명시 `--apply --branch <name>` |
| 영구 보존 | whitelist 매칭 | sweep 출력에서 skip |
| 사고 가드 | tag 가 가리키는 commit 의 branch | sweep 출력에서 skip + 경고 |

### 5-4) closed issue cleanup

- **stale 라벨 부착**: 30 일+ closed 이슈 → `stale-cleanup-YYYY-QN` label (예: `stale-cleanup-2026-Q2`).
  ```bash
  gh issue list --state closed --search "is:closed closed:<$(date -d '30 days ago' +%Y-%m-%d)" \
    --json number,closedAt,labels --limit 1000 \
    | jq -r '.[] | select((.labels | map(.name) | contains(["stale-cleanup-2026-Q2"])) | not) | .number'
  ```
  결과 issue 마다 `gh issue edit <N> --add-label "stale-cleanup-2026-Q2"`.
- **milestone 자동 close 옵션**: 모든 이슈 closed 인 milestone — `gh api repos/:owner/:repo/milestones` 조회 → `open_issues == 0` && `closed_issues > 0` → `gh api -X PATCH .../milestones/<N> -f state=closed` (운영 결정 시).
- **referent 보존**: 다음 의도적 referent 는 보존.
  - cross-PR link (이슈 본문/코멘트에 다른 active PR 번호 포함)
  - ADR 참조 (`docs/decisions/*.md` 본문에서 `#<N>` 인용)
  - feature spec `related_issues` 배열 포함

### 5-5) 운영 절차

1. **dry-run report 생성** — `bash tools/branch-cleanup/sweep.sh` (default = dry-run, `--apply` 명시 없음).
2. **report 등록** — markdown PR 본문 또는 GH issue 코멘트로 등록. nmae 가 round 단위로 자동 등록 가능.
3. **사용자 / nmae review** — 24-72 시간. 보호 / 예외 명시 결정.
4. **`--apply --yes` 1 회 호출** — 자동 삭제 카테고리만 실제 수행.
5. **결과 push** — `#모부르지` 또는 `#모부르지-digest` 채널에 결과 요약 1 줄 push.
6. **cron schedule** — monthly 권장 (또는 release tag 직전 1 회).

### 5-6) 자동 schedule (옵션)

- `.github/workflows/branch-cleanup-monthly.yml` 신설 권고.
  - cron: `0 0 1 * *` (매월 1 일 KST 09:00) — dry-run report PR 자동 생성.
  - `--apply` 는 `workflow_dispatch` manual trigger 만 — 자동 apply 금지.
- 워크플로 변경이므로 `needs-human-review` 라벨 부착 의무 (CLAUDE.md §4 보호 영역).

## 6) 도메인 / 데이터 모델

해당 없음 (인프라 정책). git ref / GitHub issue / milestone 만 다룸.

## 7) 의존 / 후속

### 의존
- **be round 2 IMPL** (PR a4cbabe31c442522b 진행 중) — `tools/branch-cleanup/sweep.sh` 확장 (closed-not-merged 식별 + gh pr list 기반 머지 식별 + `--apply --yes` flag).
- **PR #1126** (HOLD/conflict) — 본 PR 머지 후 #1126 close 또는 rebase 결정 (사용자).

### 후속
1. 1 차 dry-run report 생성 (be sub-agent 또는 nmae 수동).
2. 사용자 review (24-72h).
3. 1 차 `--apply --yes` 운영 — backlog 451 → 100 미만 drain.
4. cron schedule GHA 신설 (별도 PR, `needs-human-review`).
5. 3 개월 후 효과 측정 — remote branch 수 / closed issue 수 / 사용자 cognitive load 피드백.

## 8) 보안 / 시크릿

- 시크릿 영향 없음.
- gh CLI 는 호스트 `GH_TOKEN` 사용 — 별도 secret 도입 없음.

## 9) 관측성

- sweep 실행 결과는 `tools/branch-cleanup/sweep.log` (rotation 30 일).
- 월간 cron 결과는 Discord `#모부르지-digest` 채널에 한 줄 push:
  ```
  🧹 monthly cleanup dry-run: remote=451→N, local=M, closed-issue=416→K
  ```

## 10) 테스트 / 검증

- dry-run 호출 시 절대 삭제 수행 안 함 — `git push --delete` / `gh issue edit` 호출 없음을 단위 테스트로 검증.
- 보호 룰 매칭 — `main` / `develop` / `release/*` / open PR head / 최근 7 일 commit 5 케이스 모두 skip 되는지 검증.
- 30 일 임계 — `mergedAt` 가 정확히 30 일 경계인 케이스 (edge case) 자동 vs 수동 분기 검증.

## 11) 미해결 질문 (사용자 결정 대기)

- **Q1 자동 삭제 임계** — 30 일 (본 spec default) vs 60 일 vs 90 일. trade-off: 짧을수록 backlog drain 빠름, 길수록 사고 회복 윈도우 큼.
- **Q2 closed-not-merged branch 처리** — 즉시 삭제 vs 보존 (이유 추적용). trade-off: 즉시 삭제 = 청결, 보존 = 사후 audit 가능.
- **Q3 closed issue stale 라벨 임계** — Q1 과 동일 (30 일) 가정. 별도 임계 원하면 명시 필요.
- **Q4 cron schedule 시각** — monthly 1 일 09:00 KST 가 default. release tag 직전 1 회 vs monthly 택일 또는 병행.

## 12) 변경 이력

- 2026-05-26: 신규 작성 (PR #1127 round 3 plan sub-agent). expanded SoT 확립. PR #1126 (partial branch-only) 와 별개.
