# branch-cleanup

remote/local branch + `.claude/worktrees/agent-*` cleanup tooling. 이슈 #1127.

진입점 2종:
1. **GitHub Actions** — `.github/workflows/branch-cleanup.yml` `workflow_dispatch` (manual). PR #1147.
2. **로컬 shell** — `tools/branch-cleanup/sweep.sh` 직접 호출. PR #1128 머지.

## 배경

2026-05-26 사용자 brief — remote 446 + local 419 branch 누적 + `.claude/worktrees/agent-*` 60+ 잠긴 워크트리. PR 머지 후 head branch 삭제 안 되어 누적되었습니다.

## 정책

| category | 조건 | 처리 |
|---|---|---|
| **merged-branch** | PR `merged` + merge 시점 30일+ 경과 | remote + local 삭제 |
| **stale-branch** | open PR 없음 + last commit 60일+ | remote + local 삭제 |
| **agent-worktree** | `.claude/worktrees/agent-*` path 존재 | `git worktree remove --force` |
| **protected** | `main` / `develop` / `HEAD` / 현재 checkout / open PR head | 절대 삭제 X (whitelist) |

`release/*` / `hotfix/*` 패턴: 현재 sweep.sh 의 `PROTECTED_BRANCHES` 화이트리스트는 `main` / `develop` / `HEAD` / 현재 checkout 만 명시합니다. `release/*` / `hotfix/*` 가 open PR 을 보유 중이면 stale 분류에서 자동 제외되지만, open PR 없이 머지 30일+ 경과한 경우 merged-branch 후보로 분류될 수 있습니다. 보존이 필요하면 향후 sweep.sh `PROTECTED_BRANCHES` 또는 패턴 매처를 확장하십시오 (#1127 후속 작업 후보).

## 사용 — GitHub Actions (`workflow_dispatch` 진입점)

`.github/workflows/branch-cleanup.yml` 가 cron 없이 manual trigger 만 지원합니다 (사용자가 dry-run 결과 확인 후 `apply=true` 로 재실행하는 2-step 흐름).

### trigger 방법

1. GitHub UI: **Actions → branch-cleanup → Run workflow**.
2. CLI: `gh workflow run branch-cleanup.yml -f apply=false -f merged_days=30 -f stale_days=60`

### inputs (workflow_dispatch)

| input | type | default | 의미 |
|---|---|---|---|
| `apply` | boolean | `false` | `false`=dry-run 분류만 / `true`=remote + local + worktree 실 삭제 |
| `merged_days` | string (int) | `'30'` | PR 머지 후 N일 경과한 head branch 대상 |
| `stale_days` | string (int) | `'60'` | open PR 없는 branch + last commit N일+ 대상 |

### 산출물 (artifact)

workflow 종료 후 **Actions run summary** 페이지의 **Artifacts** 섹션에서 다운로드:

- `sweep-report` — `sweep-report-<ISO_TS>.txt` + `sweep-output.log` 묶음 (보관 30일).
- 동일 페이지 **Summary** 패널에 mode / merged_days / stale_days + report header 20줄 표시.

### permissions

- `contents: write` — remote branch 삭제 (`git push origin --delete`).
- `pull-requests: read` — `gh pr list` 조회.

### 보호 영역 ([CLAUDE.md §4](../../CLAUDE.md))

`.github/workflows/**` 는 AI 작업 보호 영역. workflow yml 변경 PR 은 `needs-human-review` 라벨 필수.

## 사용 — 로컬 shell (`sweep.sh`)

```bash
# dry-run + 통계만 (실 삭제 없음, default)
bash tools/branch-cleanup/sweep.sh

# 실 삭제 모드 (interactive confirm prompt)
bash tools/branch-cleanup/sweep.sh --apply

# 자동화 (CI / workflow_dispatch) — confirm prompt skip
bash tools/branch-cleanup/sweep.sh --apply --yes
```

### dry-run / apply 분기

| 모드 | 명령 | 동작 |
|---|---|---|
| **dry-run** (default) | `bash sweep.sh` | 분류만. report 파일 + sample 10건 stdout. 종료 코드 0. |
| **apply + interactive** | `bash sweep.sh --apply` | 분류 후 sample 5건 + `proceed? [y/N]` prompt. `y` 시 삭제. |
| **apply + 자동화** | `bash sweep.sh --apply --yes` | confirm skip. workflow_dispatch 에서 사용. |

### sweep.sh 실 인자 (정확한 이름)

> **발견 사항 (PR #1147 audit)**: 초기 task brief 에 `--active-days` 표기가 있었지만 실제 sweep.sh 가 노출하는 flag 는 **`--stale-days`** 입니다. `--active-days` 는 미구현. workflow yml 의 `stale_days` input 도 `--stale-days` 로 forward 됩니다.

| flag | 값 | default | 의미 |
|---|---|---|---|
| `--apply` | (no value) | off | 실 삭제 모드 진입. 미지정 시 dry-run. |
| `--yes` | (no value) | off | apply 모드 interactive confirm prompt skip. |
| `--merged-days` | int | `30` | PR 머지 후 N일 경과한 head branch 대상 (merged-branch). |
| `--stale-days` | int | `60` | open PR 없는 branch + last commit N일+ 대상 (stale-branch). |
| `--limit-merged` | int | `500` | `gh pr list --state merged` page 한도. |
| `--merged-only` | (no value) | off | merged-branch 만 (stale 제외). |
| `--stale-only` | (no value) | off | stale-branch 만 (merged 제외). |
| `--remote-only` | (no value) | off | remote 만 (local + worktree 건드리지 않음). |
| `--local-only` | (no value) | off | local 만. |
| `--no-worktree` | (no value) | off | worktree 수집 안 함. |
| `--no-report` | (no value) | off | report 파일 미작성. |
| `-h` / `--help` | (no value) | — | 사용법 출력 후 종료. |

### 사용 예 — 결합

```bash
# 머지 60일+ remote 만 자동 삭제 + 통계 report
bash tools/branch-cleanup/sweep.sh --merged-only --merged-days 60 --remote-only --apply --yes

# stale 90일+ 만 dry-run
bash tools/branch-cleanup/sweep.sh --stale-only --stale-days 90

# worktree 만 정리 (branch 건드리지 않음)
bash tools/branch-cleanup/sweep.sh --no-report --apply
# (worktree 만 정리하려면 --no-worktree 반대 — 본 결합은 worktree + branch 모두지만
#  --remote-only --local-only 둘 다 부여 안 하면 default 모두 활성)
```

## report

dry-run / apply 모두 `${REPO_ROOT}/sweep-report-<ISO_TS>.txt` 에 결과 저장 (`--no-report` 로 끔).

### 형식

```
# sweep-report 20260526T062500Z
# REPO=/home/mobruji/mobruji-be
# APPLY=0 MERGED_DAYS=30 STALE_DAYS=60
# 형식: <category>|<branch_or_path>|<pr_or_dash>|<extra>

merged-30d+|feat/foo-#42|#100|2026-04-15T...
stale-60d+|feat/abandoned-#88|-|1729000000
agent-worktree|/path/.claude/worktrees/agent-abc|-|locked=1
```

### artifact 다운로드 위치 (GitHub Actions)

workflow_dispatch 실행 시 `sweep-report-<ts>.txt` 와 `sweep-output.log` 가 `sweep-report` artifact 로 묶여 업로드됩니다 (`retention-days: 30`).

다운로드: **Actions → branch-cleanup → 해당 run → Artifacts → sweep-report**.

## 테스트

```bash
bash tools/branch-cleanup/test_sweep.sh
```

inline shell 테스트 — 문법 / dry-run exit 코드 / protected branch 가드 / `--remote-only` / `--apply` confirm 검증.

## 미구현 / 후속 작업

- `--issues` flag (closed issue stale 라벨) — 현재 sweep.sh 미구현. issue 라벨링은 별도 GitHub Actions (`stale-pr-watch.yml` 등) 가 담당. 본 도구의 sweep 범위는 **branch + worktree 만**. 필요 시 #1127 후속 이슈로 분리하여 `--issues` flag 신설.
- `release/*` / `hotfix/*` 패턴 명시적 화이트리스트 — 위 정책 표 참고. 현재는 open PR 보호에 의존.

## 1회 sweep 결과 (#1127 머지 시점)

PR body 에 첨부 — TBD.

## 안티패턴

- `git push --force-with-lease` 또는 `git branch -D` 직접 호출하지 말고 본 스크립트 통해. protected list + dry-run 안전 장치.
- `.claude/worktrees/agent-*` 도 절대 `rm -rf` 직접 X — git worktree state 가 garbage 됨. 반드시 `git worktree remove`.
- workflow_dispatch `apply=true` 를 즉시 trigger 하지 말 것. **반드시 `apply=false` (dry-run) 먼저 실행 → artifact report 확인 → 다시 `apply=true` 로 trigger** (2-step 흐름).
