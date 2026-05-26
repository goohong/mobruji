# branch-cleanup

remote/local branch + `.claude/worktrees/agent-*` cleanup tooling. issue #1127.

## 배경

2026-05-26 사용자 brief — remote 446 + local 419 branch 누적 + `.claude/worktrees/agent-*` 60+ 잠긴 워크트리. PR 머지 후 head branch 삭제 안 되어 누적되었습니다.

## 정책

| category | 조건 | 처리 |
|---|---|---|
| **merged-branch** | PR `merged` + merge 시점 30일+ 경과 | remote + local 삭제 |
| **stale-branch** | open PR 없음 + last commit 60일+ | remote + local 삭제 |
| **agent-worktree** | `.claude/worktrees/agent-*` path 존재 | `git worktree remove --force` |
| **protected** | `main` / `develop` / `HEAD` / 현재 checkout / open PR head | 절대 삭제 X (whitelist) |

## 사용

```bash
# dry-run + 통계만 (실 삭제 없음)
bash tools/branch-cleanup/sweep.sh

# 옵션
bash tools/branch-cleanup/sweep.sh --merged-only       # 머지된 branch 만 (stale 제외)
bash tools/branch-cleanup/sweep.sh --stale-only        # stale 만 (merged 제외)
bash tools/branch-cleanup/sweep.sh --remote-only       # remote 만 (local 건드리지 않음)
bash tools/branch-cleanup/sweep.sh --local-only        # local 만
bash tools/branch-cleanup/sweep.sh --no-worktree       # worktree 수집 안 함
bash tools/branch-cleanup/sweep.sh --merged-days 60    # 30 → 60일 cutoff
bash tools/branch-cleanup/sweep.sh --stale-days 90     # 60 → 90일 cutoff
bash tools/branch-cleanup/sweep.sh --limit-merged 1000 # gh API 페이지 한도 (default 500)

# 실 삭제 모드 (confirm prompt — `--yes` 로 skip 가능)
bash tools/branch-cleanup/sweep.sh --apply
bash tools/branch-cleanup/sweep.sh --apply --yes       # 자동화 용 (CI 등)

# 결합 예: 머지 60일+ 만 자동 삭제 + 통계 report
bash tools/branch-cleanup/sweep.sh --merged-only --merged-days 60 --apply --yes
```

## report

dry-run / apply 모두 `${REPO_ROOT}/sweep-report-<ISO_TS>.txt` 에 결과 저장 (`--no-report` 로 끔).

형식:
```
# sweep-report 20260526T062500Z
# REPO=/home/mobruji/mobruji-be
# APPLY=0 MERGED_DAYS=30 STALE_DAYS=60
# 형식: <category>|<branch_or_path>|<pr_or_dash>|<extra>

merged-30d+|feat/foo-#42|#100|2026-04-15T...
stale-60d+|feat/abandoned-#88|-|1729000000
agent-worktree|/path/.claude/worktrees/agent-abc|-|locked=1
```

## 테스트

```bash
bash tools/branch-cleanup/test_sweep.sh
```

inline shell 테스트 — 문법 / dry-run exit 코드 / protected branch 가드 / --remote-only / --apply confirm 검증.

## 1회 sweep 결과 (#1127 머지 시점)

PR body 에 첨부 — TBD.

## 안티패턴

- `git push --force-with-lease` 또는 `git branch -D` 직접 호출하지 말고 본 스크립트 통해. protected list + dry-run 안전 장치.
- `.claude/worktrees/agent-*` 도 절대 `rm -rf` 직접 X — git worktree state 가 garbage 됨. 반드시 `git worktree remove`.
